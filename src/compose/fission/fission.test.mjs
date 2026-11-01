import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { LaneGroup, GROUP_STATES, validatePrompts } from "./group.mjs";
import { Fission } from "./fission.mjs";

const group = (prompts) =>
  new LaneGroup({
    groupId: "g1",
    owner: "w1",
    lanes: prompts.map((prompt, index) => ({ index, laneId: `g1#${index}`, prompt })),
  });

test("a prompt list needs two non-blank items", () => {
  assert.deepEqual(validatePrompts(["a"]), { ok: false, rejection: "TooFewLanes" });
  assert.deepEqual(validatePrompts(["a", "  "]), {
    ok: false,
    rejection: "EmptyLanePrompt",
    index: 1,
  });
  assert.deepEqual(validatePrompts(["a", "b\nc"]), { ok: true });
  assert.throws(() => validatePrompts("ab"), /array/);
});

test("merge order and the terminal lane are topology, not arrival", () => {
  const lanes = group(["a", "b", "c"]);
  assert.deepEqual(lanes.mergeOrder(), [0, 1, 2]);
  assert.equal(lanes.terminalLane, 2);
  assert.equal(lanes.successorOf(0), 1);
  assert.equal(lanes.successorOf(2), 0);
});

test("ConvergenceReady needs every lane and every broadcast", () => {
  const lanes = group(["a", "b"]);
  lanes.start();
  lanes.replace();
  assert.equal(lanes.convergenceReady(), false);
  lanes.deliver(0);
  assert.equal(lanes.convergenceReady(), false);
  lanes.deliver(1);
  assert.equal(lanes.convergenceReady(), true);
  assert.equal(lanes.state, GROUP_STATES.READY);
});

test("only the terminal lane announces, and only when ready", () => {
  const lanes = group(["a", "b", "c"]);
  lanes.start();
  lanes.replace();
  lanes.deliver(0);
  assert.throws(() => lanes.converge({ lane: 2 }), /not ConvergenceReady/);
  lanes.deliver(1);
  lanes.deliver(2);
  assert.throws(() => lanes.converge({ lane: 1 }), /may not announce/);
  const receipt = lanes.converge({ lane: 2, result: "done" });
  assert.equal(receipt.ok, true);
  assert.equal(lanes.state, GROUP_STATES.CONVERGED);
});

test("terminal states are one-shot; the same value is idempotent", () => {
  const lanes = group(["a", "b"]);
  lanes.start();
  lanes.replace();
  lanes.fail("boom");
  assert.equal(lanes.fail("boom").ok, true);
  assert.throws(() => lanes.fail("other"), (error) => error.code === "TERMINAL_ALREADY_SET");
  assert.throws(() => lanes.converge({ lane: 1 }), (error) => error.code === "TERMINAL_ALREADY_SET");
});

test("a broadcast reaches each lane once", () => {
  const lanes = group(["a", "b"]);
  lanes.start();
  lanes.replace();
  lanes.broadcast({ key: "c1" });
  lanes.deliver(0);
  lanes.deliver(1);
  assert.equal(lanes.convergenceReady(), false);
  assert.equal(lanes.claimBroadcast(0, "c1"), true);
  assert.equal(lanes.claimBroadcast(0, "c1"), false);
  assert.equal(lanes.convergenceReady(), false);
  lanes.claimBroadcast(1, "c1");
  assert.equal(lanes.convergenceReady(), true);
});

test("a broadcast claim needs a real lane", () => {
  const lanes = group(["a", "b"]);
  assert.throws(() => lanes.claimBroadcast(5, "c1"), /no lane 5/);
  assert.throws(() => lanes.deliver(-1), /no lane -1/);
});

// A port whose steps can be made to fail, to prove rollback.
const port = ({ failAt = null, calls = [] } = {}) => {
  const step = (name, value) => async () => {
    calls.push(name);
    if (failAt === name) throw new Error(`${name} failed`);
    return value;
  };
  return {
    calls,
    ownerView: step("ownerView", "view"),
    createLane: step("createLane", { ok: true }),
    persistGroup: step("persistGroup", { ok: true }),
    startLane: step("startLane", { ok: true }),
    replacePresent: step("replacePresent", { ok: true }),
    abortLanes: step("abortLanes", { ok: true }),
  };
};

const fissionWith = (hostPort) =>
  new Fission({
    port: hostPort,
    origins: (name) => name === "w1",
    capacity: (count) => count <= 8,
  });

// A port that refuses one step on the first attempt and works afterwards, so a
// failed group can be shown to leave nothing behind.
const portRefusingOnce = (failAt, { receipt = false } = {}) => {
  let refused = false;
  const calls = [];
  const step = (name, value) => async () => {
    calls.push(name);
    if (name === failAt && !refused) {
      refused = true;
      if (receipt) return { ok: false, reason: `${name.toUpperCase()}_REFUSED` };
      throw new Error(`${name} failed`);
    }
    return value;
  };
  return {
    calls,
    ownerView: step("ownerView", "view"),
    createLane: step("createLane", { ok: true }),
    persistGroup: step("persistGroup", { ok: true }),
    startLane: step("startLane", { ok: true }),
    replacePresent: step("replacePresent", { ok: true }),
    abortLanes: step("abortLanes", { ok: true }),
  };
};

test("fission refuses before creating anything", async () => {
  const host = port();
  const fission = fissionWith(host);
  assert.equal((await fission.fission("top", ["a", "b"])).rejection, "InvalidOrigin");
  assert.equal((await fission.fission("w1", ["a"])).rejection, "TooFewLanes");
  assert.equal((await fission.fission("w1", ["a", " "])).rejection, "EmptyLanePrompt");
  assert.deepEqual(host.calls, []);
});

test("fission creates, persists, starts and replaces in order", async () => {
  const host = port();
  const fission = fissionWith(host);
  const receipt = await fission.fission("w1", ["a", "b"]);
  assert.equal(receipt.ok, true);
  assert.deepEqual(
    receipt.lanes.map((lane) => lane.index),
    [0, 1],
  );
  assert.deepEqual(host.calls, [
    "ownerView",
    "createLane",
    "createLane",
    "persistGroup",
    "startLane",
    "startLane",
    "replacePresent",
  ]);
  assert.equal((await fission.fission("w1", ["c", "d"])).rejection, "AlreadyActive");
});

test("a second fission while the first group is being built is ConflictingGroup", async () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const calls = [];
  const host = {
    ownerView: async () => {
      calls.push("ownerView");
      await gate;
      return "view";
    },
    createLane: async (lane) => {
      calls.push(`createLane:${lane.index}`);
      return { ok: true };
    },
    persistGroup: async () => ({ ok: true }),
    startLane: async () => ({ ok: true }),
    replacePresent: async () => ({ ok: true }),
    abortLanes: async () => ({ ok: true }),
  };
  const fission = fissionWith(host);
  const first = fission.fission("w1", ["a", "b"]);
  // The first build is parked inside `ownerView`; the second call must not
  // start a competing build.
  assert.equal((await fission.fission("w1", ["c", "d"])).rejection, "ConflictingGroup");
  assert.deepEqual(calls, ["ownerView"], "no lane of the refused build was created");
  release();
  assert.equal((await first).ok, true);
  assert.deepEqual(
    calls.filter((entry) => entry.startsWith("createLane")),
    ["createLane:0", "createLane:1"],
  );
});

test("a terminal group no longer blocks the owner, and stays addressable until replaced", async () => {
  const fission = fissionWith(port());
  const first = await fission.fission("w1", ["a", "b"]);
  assert.equal(first.ok, true);
  assert.equal(fission.settle("w1", 0, "zero").delivered, true);
  assert.equal(fission.settle("w1", 1, "one").converged?.ok, true);
  assert.equal(fission.stateOf("w1"), "converged");
  // The finished record is still the owner's group until a new one commits.
  assert.equal(fission.groupOf("w1").groupId, first.groupId);
  assert.deepEqual(
    fission.groups().map((group) => group.groupId),
    [first.groupId],
  );

  // §4.2 step 10 restored the owner to a single present, so it may fission
  // again; only an ACTIVE group refuses admission.
  const second = await fission.fission("w1", ["c", "d"]);
  assert.equal(second.ok, true);
  assert.notEqual(second.groupId, first.groupId);
  assert.equal(fission.groupOf("w1").groupId, second.groupId);
});

test("a rolled-back build gives its captured completions back to the next group", async () => {
  const host = portRefusingOnce("startLane");
  const fission = fissionWith(host);
  // A turn settled before fission. The first build captures it and fails, so it
  // must return to the pending queue rather than disappear with the rollback.
  fission.settled({ name: "pre", text: "before" });
  await assert.rejects(() => fission.fission("w1", ["a", "b"]), /startLane failed/);
  const built = await fission.fission("w1", ["a", "b"]);
  assert.equal(built.ok, true);
  fission.settle("w1", 0, "zero");
  fission.settle("w1", 1, "one");
  // Every lane delivered, but the captured completion has not been claimed:
  // condition B keeps the group out of `ready`/`converged`.
  assert.equal(fission.stateOf("w1"), "replaced");
  assert.equal(fission.claim("w1", 0, "pre"), true);
  assert.equal(fission.claim("w1", 1, "pre"), true);
  const announced = fission.settle("w1", 1, "one");
  assert.equal(announced.converged?.ok, true);
  assert.equal(fission.stateOf("w1"), "converged");
});

test("a failure after admission aborts the whole group", async () => {
  for (const failAt of ["createLane", "persistGroup", "startLane", "replacePresent"]) {
    // A thrown error and a refused receipt are the same fact (§4.2 step 4: a
    // failed create, persist or start aborts the lanes already built).
    for (const receipt of [false, true]) {
      const host = portRefusingOnce(failAt, { receipt });
      const fission = fissionWith(host);
      const label = `${failAt}${receipt ? " (receipt)" : ""}`;
      await assert.rejects(() => fission.fission("w1", ["a", "b"]), /failed|refused/, label);
      // Nothing is left behind: no group, so the owner can start a fresh one.
      assert.equal(fission.groupOf("w1"), null, `${label}: no group`);
      const retried = await fission.fission("w1", ["a", "b"]);
      assert.equal(retried.ok, true, `${label}: retry`);
      assert.deepEqual(
        retried.lanes.map((lane) => lane.index),
        [0, 1],
        `${label}: lanes`,
      );
    }
  }
});

test("a rollback failure is reported beside the original failure, not instead of it", async () => {
  const host = port({ failAt: "startLane" });
  const abortError = new Error("abort failed");
  host.abortLanes = async () => {
    throw abortError;
  };
  const fission = fissionWith(host);
  await assert.rejects(
    () => fission.fission("w1", ["a", "b"]),
    (error) => /startLane failed/.test(error.message) && error.abortFailure === abortError,
  );
  assert.equal(fission.groupOf("w1"), null);
});

test("the owner view must be text or nothing", async () => {
  const host = port();
  host.ownerView = async () => ({ messages: [] });
  const fission = fissionWith(host);
  await assert.rejects(() => fission.fission("w1", ["a", "b"]), /ownerView/);
  assert.equal(fission.groupOf("w1"), null);
});

test("capacity is checked for the whole group", async () => {
  const host = port();
  const fission = fissionWith(host);
  const prompts = Array.from({ length: 9 }, (_, index) => `p${index}`);
  assert.equal((await fission.fission("w1", prompts)).rejection, "CapacityExceeded");
  assert.deepEqual(host.calls, []);
});

test("every delivery order reaches ConvergenceReady", async () => {
  await fc.assert(
    fc.asyncProperty(fc.shuffledSubarray([0, 1, 2, 3], { minLength: 4, maxLength: 4 }), async (order) => {
      const lanes = group(["a", "b", "c", "d"]);
      lanes.start();
      lanes.replace();
      for (const index of order) lanes.deliver(index);
      assert.equal(lanes.convergenceReady(), true);
      assert.deepEqual(lanes.mergeOrder(), [0, 1, 2, 3]);
    }),
  );
});
