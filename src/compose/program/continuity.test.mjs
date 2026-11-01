// Behavior tests for the composition fixes (spec §2.2, §2.3, §2.4, §3.3, §4.2).
//
// The spec sentences under test:
//   §4.2  "ConvergenceReady 不成立时不得宣布" and "合并次序为 0..n−1" — the
//         announcement must not depend on when a delivery arrives;
//   §4.2  "整组准入或整组拒绝 … 不留部分组" — a refused group leaves no id;
//   根 §2.3: retirement, barrier and successor are one record — two reviews must
//         not both relay;
//   §2.2  a handoff starts a Manager session or it fails;
//   §3.3  a parked program leaves `parked` only by resume.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { LaneGroup, GROUP_STATES } from "../fission/group.mjs";
import { Fission } from "../fission/fission.mjs";
import { ReviewLedger, REVIEW_KINDS } from "../../core/review.mjs";
import { ReviewMember } from "../relay/review.mjs";
import { ProgramState } from "../../core/program.mjs";
import { ProgramSurface, ROLES } from "./surface.mjs";
import { fakeClock } from "../../core/clock.mjs";

// 根 §3.3: a receipt names the program it ran, so every surface here is built
// with one. It is never executed: these tests call members directly.
const SOURCE = "class Js extends JsProgram {\n  async run() { return null; }\n}\n";


const group = (count) =>
  new LaneGroup({
    groupId: "g",
    owner: "w",
    lanes: Array.from({ length: count }, (_, index) => ({
      index,
      laneId: `g#${index}`,
      prompt: `p${index}`,
    })),
  });

const started = (count) => {
  const lanes = group(count);
  lanes.start();
  lanes.replace();
  return lanes;
};

test("the terminal lane may announce before reporting its own delivery", () => {
  const lanes = started(3);
  lanes.deliver(0);
  lanes.deliver(1);
  // Lane 2 is the announcing lane and has not delivered yet: announcing is the
  // act of closing, so the group must converge rather than refuse.
  const receipt = lanes.converge({ lane: 2, result: "done" });
  assert.equal(receipt.ok, true);
  assert.equal(lanes.state, GROUP_STATES.CONVERGED);
});

test("an announcement still needs every other lane and every broadcast", () => {
  const lanes = started(3);
  lanes.deliver(0);
  // Lane 1 never delivered: the announcing lane cannot speak for it.
  assert.throws(() => lanes.converge({ lane: 2 }), /not ConvergenceReady/);
  // The refused announcement left no trace: lane 2 is still open.
  assert.equal(lanes.successorOf(1), 2);
  lanes.deliver(1);
  assert.equal(lanes.converge({ lane: 2 }).ok, true);
});

test("a refused announcement does not count the announcing lane as delivered", () => {
  const lanes = started(2);
  lanes.broadcast({ key: "c1" });
  lanes.claimBroadcast(0, "c1");
  // Lane 0 delivered, the broadcast is claimed by lane 0 only.
  lanes.deliver(0);
  assert.throws(() => lanes.converge({ lane: 1 }), /not ConvergenceReady/);
  // Lane 1 is the only open lane, and a lane is never its own successor.
  assert.equal(lanes.successorOf(1), null, "no OTHER open lane");
});

test("a terminal group takes no further deliveries", () => {
  const lanes = started(2);
  lanes.deliver(0);
  lanes.deliver(1);
  lanes.converge({ lane: 1, result: "merged" });
  assert.throws(() => lanes.deliver(0), (error) => error.code === "TERMINAL_ALREADY_SET");
  assert.throws(() => lanes.broadcast({ key: "late" }), (error) => error.code === "TERMINAL_ALREADY_SET");
  assert.throws(() => lanes.claimBroadcast(0, "late"), (error) => error.code === "TERMINAL_ALREADY_SET");
  const failed = started(2);
  failed.fail("boom");
  assert.throws(() => failed.deliver(0), (error) => error.code === "TERMINAL_ALREADY_SET");
});

test("the merge order and the terminal lane are the same for every arrival order", () => {
  fc.assert(
    fc.property(
      fc.integer({ min: 2, max: 5 }),
      fc.boolean(),
      (count, terminalDeliversLate) => {
        const lanes = started(count);
        const order = Array.from({ length: count }, (_, index) => index);
        for (const index of order) {
          // Lane count-1 may or may not have reported its delivery first.
          if (index === count - 1 && terminalDeliversLate) continue;
          lanes.deliver(index);
        }
        assert.deepEqual(lanes.mergeOrder(), order);
        assert.equal(lanes.terminalLane, count - 1);
        const receipt = lanes.converge({ lane: count - 1, result: "r" });
        assert.equal(receipt.ok, true);
        assert.equal(lanes.state, GROUP_STATES.CONVERGED);
      },
    ),
  );
});

// A port whose step answers with a refused receipt, to prove the rollback runs.
test("a refused group leaves no id behind, so a retry gets a fresh one", async () => {
  const calls = [];
  let refuse = true;
  const port = {
    ownerView: async () => "view",
    createLane: async (lane) => {
      calls.push(`create:${lane.laneId}`);
      return { ok: true };
    },
    persistGroup: async () => ({ ok: true }),
    startLane: async () => (refuse ? { ok: false, reason: "NO_ROOM" } : { ok: true }),
    replacePresent: async () => ({ ok: true }),
    abortLanes: async ({ groupId, laneIds }) => {
      calls.push(`abort:${groupId}:${laneIds.length}`);
      return { ok: true, released: laneIds, orphaned: [] };
    },
  };
  const fission = new Fission({ port, origins: (name) => name === "w1", capacity: () => true });
  await assert.rejects(() => fission.fission("w1", ["a", "b"]), /startLane was refused/);
  refuse = false;
  const receipt = await fission.fission("w1", ["a", "b"]);
  assert.equal(receipt.ok, true);
  const first = calls.find((entry) => entry.startsWith("abort:")).split(":")[1];
  assert.notEqual(receipt.groupId, first, "a retry never reuses a handed-out group id");
});

test("two overlapping reviews do not both relay", async () => {
  let handOvers = 0;
  const port = {
    handOver: async () => {
      handOvers += 1;
      // A slow host: the second review starts while this one is in flight.
      await new Promise((resolve) => setImmediate(resolve));
      return { ok: true, retired: true, barrier: true, successor: "m2" };
    },
    startManager: async () => ({ ok: true }),
    askOperator: async () => "go",
  };
  const ledger = new ReviewLedger({ kind: "manager" });
  const member = new ReviewMember({ ledger, port });
  await member.review([{ key: "a", value: "x" }]);
  // Clear the obligation first: only an EMPTY ledger relays (根 §2.4), and an
  // empty review while `a` is still open is just `recorded`.
  const clearing = await member.review([{ key: "a", value: null }]);
  assert.equal(clearing.kind, "relay");
  assert.equal(handOvers, 1);
  // Two reviews start together, while the successor is in place and its
  // ledger is empty. Both read the same empty ledger; without serialization
  // both would relay, retiring the successor the first one opened.
  const [first, second] = await Promise.all([member.review([]), member.review([])]);
  assert.equal(handOvers, 1, "one relay, not two");
  // The successor's first empty review advances the milestone; the one after it
  // closes the work (根 §2.4). Neither relays again.
  assert.equal(first.kind, "confirm");
  assert.equal(second.kind, "closed");
  assert.equal(ledger.tenure, 1);
  assert.equal(ledger.milestone, "done");
});

test("a handoff the host could not start is not reported as a handoff", async () => {
  const port = {
    handOver: async () => ({ ok: true, retired: true, barrier: true, successor: "m1" }),
    startManager: async () => ({ ok: false, reason: "NO_CAPACITY" }),
    askOperator: async () => "go",
  };
  const ledger = new ReviewLedger({ kind: "plan" });
  const member = new ReviewMember({ ledger, port });
  member.ledger.review([]); // askUser
  // S1 → S2 → S3 → 待执行, one relay and one successor verdict per step
  // (根 §2.4 第 1–5 步; the relay needs the host's complete hand-over).
  for (let step = 0; step < 3; step += 1) {
    assert.equal(member.ledger.review([]).kind, REVIEW_KINDS.RELAY);
    member.ledger.openSuccessor();
    assert.equal(member.ledger.review([]).kind, REVIEW_KINDS.CONFIRM);
  }
  const receipt = await member.review([], "draft body");
  assert.equal(receipt.ok, false);
  assert.equal(receipt.kind, "handoff");
  assert.equal(receipt.rejection, "HANDOFF_UNAVAILABLE");
  assert.deepEqual(receipt.started, { ok: false, reason: "NO_CAPACITY" });
  // The Plan stays at 待执行 and may retry.
  assert.equal(ledger.milestone, "待执行");
});

test("a command that ends does not un-park a program parked meanwhile", async (t) => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  // A failed assertion before `release()` must not leave `command.run` pending:
  // a promise nobody settles keeps the event loop alive and `node --test` then
  // waits out its whole deadline instead of reporting the failure.
  t.after(() => release());
  const ports = {
    command: {
      run: async () => {
        await gate;
        return { stdout: "", stderr: "", exitCode: 0 };
      },
    },
    control: { unpark: async () => null },
    relay: { handOver: async () => null, startManager: async () => null, askOperator: async () => "" },
    devops: { investigate: async () => "" },
  };
  const state = new ProgramState("c1");
  const surface = new ProgramSurface({
    name: state.name,
    role: ROLES.DEVOPS,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports,
    root: "/tmp",
    views: new Map(),
  });
  const members = surface.surface();
  const pending = members.call("sleep 1");
  await until(() => state.state === "waiting");
  assert.equal(state.state, "waiting");
  // The body parks while the command runs; the command then ends.
  state.park({ reason: "explicit", payload: null });
  release();
  await pending;
  assert.equal(state.state, "parked", "a resume is the only way out of parked");
});

// A program whose command port records what it was asked to run, and when.
const commandSurface = (state, run) => {
  const ports = {
    command: { run },
    control: { unpark: async () => null },
    relay: { handOver: async () => null, startManager: async () => null, askOperator: async () => "" },
    devops: { investigate: async () => "" },
  };
  const surface = new ProgramSurface({
    name: state.name,
    role: ROLES.DEVOPS,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports,
    root: "/tmp",
    views: new Map(),
  });
  return surface.surface();
};

// Yield a few turns of the microtask queue and the event loop: enough for a
// command that is allowed to start to have started, and for one that must wait
// to have reached its drain.
const settleTurns = async (turns = 3) => {
  for (let index = 0; index < turns; index += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
};

// Wait for a fact the program reaches after a few turns. Each wait is bounded
// by an unref'd timer, so a fact that never arrives fails the assertion that
// follows instead of hanging the suite.
const until = async (fact) => {
  for (let turn = 0; turn < 200; turn += 1) {
    if (fact()) return true;
    await new Promise((resolve) => setTimeout(resolve, 1).unref());
  }
  return false;
};

test("a command waits for the operation that precedes it, then returns to the state it interrupted", async () => {
  const state = new ProgramState("c2");
  const seen = [];
  const members = commandSurface(state, async (command) => {
    seen.push({ command, state: state.state });
    return { stdout: "", stderr: "", exitCode: 0 };
  });
  // An SDK operation the program started before the command, still in flight.
  // §3.3: the command must not run until it settles.
  const earlier = state.beginOperation();
  let settled = false;
  state.moveTo("waiting");
  try {
    let done = false;
    const pending = members.call("true").then(() => {
      done = true;
    });
    await settleTurns();
    assert.deepEqual(seen, [], "the command must not start while an earlier operation is open");
    assert.equal(done, false);
    // The earlier operation settles; only now may the command run.
    state.endOperation(earlier);
    settled = true;
    await pending;
    assert.deepEqual(seen, [{ command: "true", state: "waiting" }]);
    // The command interrupted `waiting`; the program goes back there, not to
    // `running`, so a later expression cannot overtake the earlier wait.
    assert.equal(state.state, "waiting");
  } finally {
    // The open operation is the test's own; a failed assertion above must not
    // leave it open for the rest of the file. It is settled by its own id, so
    // the command's operation is never the one that gets settled by mistake.
    if (!settled) state.endOperation(earlier);
  }
});

test("one program runs one command at a time", async () => {
  const state = new ProgramState("c5");
  let release;
  const started = [];
  const members = commandSurface(state, async (command) => {
    started.push(command);
    await new Promise((resolve) => {
      release = resolve;
    });
    return { stdout: command, stderr: "", exitCode: 0 };
  });
  // A failed assertion must not leave `first` unsettled: an unhandled rejection
  // would be reported after the test ended, and the gate would stay pending.
  const first = members.call("first");
  first.catch(() => undefined);
  await settleTurns();
  // The first command is live. A second `call` is refused, not queued: the live
  // command is not its predecessor, so no drain could order the two.
  await assert.rejects(
    () => members.call("second"),
    (error) => error.code === "INVALID_ARGUMENT" && /running another command/.test(error.message),
  );
  assert.deepEqual(started, ["first"]);
  release();
  assert.deepEqual(await first, { stdout: "first", stderr: "", exitCode: 0 });
  // Once the first ended, the program runs the next command normally.
  const later = members.call("second");
  await settleTurns();
  release();
  assert.deepEqual(await later, { stdout: "second", stderr: "", exitCode: 0 });
  assert.equal(state.pendingOperations, 0);
  assert.equal(state.state, "running");
});

test("a second expression is refused while the program drains", async () => {
  // Spec §3.3: draining is a transition in which the program "不再接受新的
  // 表达式推进". A second command arriving while the first still waits must be
  // refused, not queued: admitting it would let it overtake the work the drain
  // exists to protect.
  const state = new ProgramState("c6");
  const started = [];
  const members = commandSurface(state, async (command) => {
    started.push(command);
    return { stdout: command, stderr: "", exitCode: 0 };
  });
  // An SDK operation the program started before any command, still in flight.
  const sdk = state.beginOperation();
  const first = members.call("A");
  first.catch(() => undefined);
  await settleTurns();
  // The program is draining: it advertises that it takes no new expression,
  // and it is `waiting` for its own earlier work (§3.3: `waiting` covers
  // waiting for a command or an SDK operation).
  assert.equal(state.draining, true);
  assert.equal(state.acceptsExpressions, false);
  assert.equal(state.state, "waiting");
  // A new expression arrives during the drain and is refused.
  await assert.rejects(
    () => members.call("B"),
    (error) => error.code === "INVALID_ARGUMENT" && /draining/.test(error.message),
  );
  assert.deepEqual(started, [], "neither command runs while the earlier operation is open");
  // The earlier operation settles; only the earlier command runs, and it runs
  // alone. B was refused outright, not queued behind A.
  state.endOperation(sdk);
  await first;
  await settleTurns();
  assert.deepEqual(started, ["A"]);
  assert.equal(state.pendingOperations, 0);
});

test("a refused call leaves no operation behind to block later drains", async () => {
  // A drain waits for operations with SMALLER ids. If a refused call had
  // registered its own operation before being refused, that id would be a
  // predecessor of everything after it, and every later drain would wait for an
  // operation nobody ever settles: a phantom that outlives the refusal. The
  // refusal therefore happens before registration, and the only operation that
  // ever existed is the test's own.
  const state = new ProgramState("c8");
  const started = [];
  const members = commandSurface(state, async (command) => {
    started.push(command);
    return { stdout: command, stderr: "", exitCode: 0 };
  });
  const sdk = state.beginOperation();
  const first = members.call("A");
  first.catch(() => undefined);
  await settleTurns();
  const before = state.pendingOperations;
  await assert.rejects(
    () => members.call("B"),
    (error) => error.code === "INVALID_ARGUMENT" && /draining/.test(error.message),
  );
  // The refused call registered nothing: the open count is exactly what it was.
  assert.equal(state.pendingOperations, before, "a refused call registers no operation");
  state.endOperation(sdk);
  await first;
  // Everything settles: no phantom id is left waiting to be released.
  assert.equal(state.pendingOperations, 0);
  assert.deepEqual(started, ["A"]);
  // A later command drains at once: nothing earlier is still open.
  assert.deepEqual(await members.call("C"), { stdout: "C", stderr: "", exitCode: 0 });
  assert.deepEqual(started, ["A", "C"]);
});

test("a caller's own registration is not a drain waiter", async () => {
  // The deadlock must not come back in disguise: if the caller's own operation
  // registered a drain, `draining` would be true for the whole command and
  // `acceptsExpressions` false, and the caller would be waiting for itself. A
  // program with nothing earlier to wait for runs its command with `draining`
  // false.
  const state = new ProgramState("c7");
  const observed = [];
  const members = commandSurface(state, async (command) => {
    observed.push({
      command,
      draining: state.draining,
      accepts: state.acceptsExpressions,
      state: state.state,
      pending: state.pendingOperations,
    });
    return { stdout: command, stderr: "", exitCode: 0 };
  });
  // No earlier operation: nothing to drain.
  assert.deepEqual(await members.call("solo"), { stdout: "solo", stderr: "", exitCode: 0 });
  assert.deepEqual(observed, [
    { command: "solo", draining: false, accepts: true, state: "waiting", pending: 1 },
  ]);
  assert.equal(state.pendingOperations, 0);
  assert.equal(state.state, "running");
  assert.equal(state.draining, false);
});

test("a surface without a command port refuses a command instead of crashing", async () => {
  const state = new ProgramState("c3");
  const surface = new ProgramSurface({
    name: state.name,
    role: ROLES.CODER,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: { relay: {}, devops: {} },
    root: "/tmp",
    views: new Map(),
  });
  await assert.rejects(() => surface.surface().call("true"), (error) => error.code === "INVALID_ARGUMENT");
  assert.equal(state.pendingOperations, 0);
});

test("a parked program is refused as parked, not as a missing command port", async () => {
  // Spec §3.3 gives one reason a program takes no new expression: it is parked.
  // A surface that also lacks a command port must still answer with the fact the
  // caller has to act on — the program must be resumed, not retried.
  const state = new ProgramState("c9");
  const surface = new ProgramSurface({
    name: state.name,
    role: ROLES.CODER,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: { relay: {}, devops: {} },
    root: "/tmp",
    views: new Map(),
  });
  state.park({ reason: "explicit", payload: null });
  await assert.rejects(
    () => surface.surface().call("true"),
    (error) => error.code === "INVALID_ARGUMENT" && /is parked/.test(error.message),
  );
  // The refusal changed nothing: the site is still there for the resume.
  assert.equal(state.state, "parked");
  assert.equal(state.pendingOperations, 0);
});

test("a park receipt keeps the nine fields and carries the injected value", () => {
  const state = new ProgramState("r1");
  const surface = new ProgramSurface({
    name: state.name,
    role: ROLES.CODER,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: { relay: {}, devops: {} },
    root: "/tmp",
    views: new Map(),
  });
  const receipt = surface.receipt({ value: [1, 2] });
  assert.equal(Object.keys(receipt).length, 9);
  assert.deepEqual(receipt.value, [1, 2]);
  const final = surface.finalReceipt();
  assert.equal(Object.keys(final).length, 9);
  assert.equal(final.result, null);
});
