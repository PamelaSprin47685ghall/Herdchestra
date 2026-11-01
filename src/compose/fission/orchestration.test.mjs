import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { validatePrompts } from "./group.mjs";
import { Fission } from "./fission.mjs";

// A port that records what the orchestrator did, in order.
const hostPort = () => {
  const calls = [];
  const lanes = [];
  const record = (name, value) => async () => {
    calls.push(name);
    return value;
  };
  return {
    calls,
    lanes,
    ownerView: record("ownerView", "last-view"),
    // A two-step host: the lane is built inert here, and started later.
    buildLane: async (lane) => {
      calls.push(`buildLane:${lane.index}`);
      lanes.push(lane);
      return { laneId: lane.laneId };
    },
    persistGroup: async (group) => {
      calls.push("persistGroup");
      return { ok: true, group };
    },
    startLane: async (lane) => {
      calls.push(`startLane:${lane.laneId}`);
      return { ok: true };
    },
    replacePresent: record("replacePresent", { ok: true }),
    abortLanes: record("abortLanes", { ok: true }),
  };
};

const fissionOf = (port, { origins = ["w1"], capacity = () => true } = {}) =>
  new Fission({ port, origins: (name) => origins.includes(name), capacity });

test("the lane prompt list is checked before anything is created", () => {
  assert.deepEqual(validatePrompts([]), { ok: false, rejection: "TooFewLanes" });
  assert.deepEqual(validatePrompts(["only"]), { ok: false, rejection: "TooFewLanes" });
  assert.deepEqual(validatePrompts(["a", "\n\t"]), {
    ok: false,
    rejection: "EmptyLanePrompt",
    index: 1,
  });
  assert.deepEqual(validatePrompts(["a", "b\nc"]), { ok: true });
});

test("the owner view reaches every lane", async () => {
  const port = hostPort();
  const seen = [];
  const original = port.buildLane;
  port.buildLane = async (lane) => {
    seen.push(lane.view);
    return original(lane);
  };
  const fission = fissionOf(port);
  const receipt = await fission.fission("w1", ["one", "two", "three"]);
  assert.equal(receipt.ok, true);
  assert.deepEqual(seen, ["last-view", "last-view", "last-view"]);
  assert.deepEqual(
    receipt.lanes.map((lane) => lane.index),
    [0, 1, 2],
  );
});

test("every lane of a group carries the group's lane count", async () => {
  const port = hostPort();
  const fission = fissionOf(port);
  await fission.fission("w1", ["one", "two", "three"]);
  // The count is the group's, the same in every lane; it is not the index.
  assert.deepEqual(
    port.lanes.map((lane) => lane.laneCount),
    [3, 3, 3],
  );
});

// Spec §4.2 step 3 puts `lane_count` in every lane's start context. Whatever
// the prompt list, the host must receive one lane per prompt and one count —
// the group's own — in all of them.
test("any lane count reaches the host as the group's count in every lane", async () => {
  await fc.assert(
    fc.asyncProperty(
      // A blank prompt is refused before a lane exists (`EmptyLanePrompt`), so
      // the generator admits only what admission admits.
      fc.array(fc.stringMatching(/^[a-z][a-z0-9 ]{0,11}$/), { minLength: 2, maxLength: 8 }),
      async (prompts) => {
        const port = hostPort();
        const fission = fissionOf(port);
        const receipt = await fission.fission("w1", prompts);
        assert.equal(receipt.ok, true);
        assert.equal(port.lanes.length, prompts.length);
        assert.deepEqual(
          port.lanes.map((lane) => lane.laneCount),
          prompts.map(() => prompts.length),
        );
        assert.deepEqual(
          port.lanes.map((lane) => lane.index),
          prompts.map((_, index) => index),
        );
        // The receipt's lanes are the same facts the host was given.
        assert.deepEqual(
          receipt.lanes.map((lane) => lane.laneCount),
          prompts.map(() => prompts.length),
        );
      },
    ),
  );
});

test("the group is replaced only after every lane started", async () => {
  const port = hostPort();
  const fission = fissionOf(port);
  await fission.fission("w1", ["a", "b"]);
  // Spec §4.2 steps 4 to 6: persist, start every lane, then replace the owner's
  // present. The lane group follows the same order.
  assert.deepEqual(port.calls, [
    "ownerView",
    "buildLane:0",
    "buildLane:1",
    "persistGroup",
    "startLane:w1:2:1#0",
    "startLane:w1:2:1#1",
    "replacePresent",
  ]);
  assert.equal(fission.stateOf("w1"), "replaced");
});

test("a lane delivery before the group exists is a refusal", async () => {
  const fission = fissionOf(hostPort());
  assert.throws(() => fission.deliver("w1", 0), /no fission group/);
  assert.throws(() => fission.broadcast("w1", { key: "c" }), /no fission group/);
  assert.throws(() => fission.fail("w1", "boom"), /no fission group/);
});

test("the terminal lane announces the merge and the group converges once", async () => {
  const port = hostPort();
  const fission = fissionOf(port);
  const { groupId } = await fission.fission("w1", ["a", "b"]);
  assert.equal(await fission.deliver("w1", 0, "lane zero"), false);
  // The takeover lane's delivery completes the conjunction and announces with
  // its own report.
  assert.equal(await fission.deliver("w1", 1, "merged"), true);
  assert.equal(fission.stateOf("w1"), "converged");
  // A second announcement of the same result is idempotent.
  const receipt = fission.converge("w1", 1, "merged");
  assert.equal(receipt.ok, true);
  assert.equal(receipt.groupId, groupId);
  assert.equal(fission.stateOf("w1"), "converged");
  assert.throws(() => fission.converge("w1", 1, "other"), (error) => error.code === "TERMINAL_ALREADY_SET");
});

test("a group that fails is terminal and keeps its one value", async () => {
  const fission = fissionOf(hostPort());
  await fission.fission("w1", ["a", "b"]);
  assert.equal(fission.fail("w1", "boom").ok, true);
  assert.equal(fission.stateOf("w1"), "failed");
  assert.equal(fission.fail("w1", "boom").ok, true);
  assert.throws(() => fission.fail("w1", "other"), (error) => error.code === "TERMINAL_ALREADY_SET");
});

// §4.2 step 4: the group's record is durable BEFORE any lane runtime runs. The
// lanes are built inert first (a runtime exists, no turn), the record is
// committed, and only then does the first lane start. A host that could start a
// lane at build time would leave a running lane with no record if the commit
// failed, which is the crash window this ordering closes.
test("the group record is committed after every lane is built and before any lane starts", async () => {
  const order = [];
  const port = {
    ownerView: async () => null,
    buildLane: async (lane) => {
      order.push(`build:${lane.index}`);
      return { laneId: lane.laneId };
    },
    persistGroup: async ({ groupId }) => {
      order.push("commit");
      return { ok: true, durable: true, groupId };
    },
    startLane: async (lane) => {
      order.push(`start:${lane.index}`);
      return { ok: true };
    },
    replacePresent: async () => {
      order.push("replace");
      return { ok: true };
    },
    abortLanes: async () => {
      order.push("abort");
      return { ok: true, released: [], orphaned: [] };
    },
    discardGroup: async () => {
      order.push("discard");
      return { ok: true };
    },
  };
  const fission = fissionOf(port);
  const receipt = await fission.fission("w1", ["a", "b", "c"]);
  assert.equal(receipt.ok, true);
  assert.deepEqual(order, [
    "build:0",
    "build:1",
    "build:2",
    "commit",
    "start:0",
    "start:1",
    "start:2",
    "replace",
  ]);
});

test("a commit that fails starts no lane and hands back every lane it built", async () => {
  const built = [];
  const aborted = [];
  let discarded = null;
  let started = 0;
  const port = {
    ownerView: async () => null,
    buildLane: async (lane) => {
      built.push(lane.laneId);
      return { laneId: lane.laneId };
    },
    // The host could not make the record durable: step 4 did not happen.
    persistGroup: async () => ({ ok: false, reason: "JOURNAL_WRITE_FAILED" }),
    startLane: async () => {
      started += 1;
      return { ok: true };
    },
    replacePresent: async () => ({ ok: true }),
    abortLanes: async ({ laneIds }) => {
      aborted.push(...laneIds);
      return { ok: true, released: laneIds, orphaned: [] };
    },
    discardGroup: async () => {
      discarded = "called";
      return { ok: true };
    },
  };
  const fission = fissionOf(port);
  await assert.rejects(
    () => fission.fission("w1", ["a", "b"]),
    (error) => error.code === "JOURNAL_WRITE_FAILED" && Array.isArray(error.rollback?.released),
  );
  assert.equal(started, 0, "no lane runs without a durable record");
  assert.deepEqual(aborted, built, "every lane built is handed back");
  assert.equal(discarded, null, "nothing was committed, so nothing is withdrawn");
  // The owner keeps its old present and may try again.
  assert.equal(fission.groupOf("w1"), null);
});

test("a lane that fails to start rolls the committed group back, record included", async () => {
  const events = [];
  const port = {
    ownerView: async () => null,
    buildLane: async (lane) => ({ laneId: lane.laneId }),
    persistGroup: async () => ({ ok: true, durable: true }),
    startLane: async (lane) => {
      events.push(`start:${lane.index}`);
      return lane.index === 1 ? { ok: false, reason: "LANE_START_FAILED" } : { ok: true };
    },
    replacePresent: async () => ({ ok: true }),
    abortLanes: async ({ laneIds }) => {
      events.push(`abort:${laneIds.length}`);
      return { ok: true, released: laneIds, orphaned: [] };
    },
    discardGroup: async () => {
      events.push("discard");
      return { ok: true };
    },
  };
  const fission = fissionOf(port);
  await assert.rejects(() => fission.fission("w1", ["a", "b", "c"]), /LANE_START_FAILED/);
  // The record is withdrawn before the lanes are stopped: the group is about to
  // not exist, so no reader may still find it.
  assert.deepEqual(events, ["start:0", "start:1", "discard", "abort:3"]);
  assert.equal(fission.groupOf("w1"), null, "a rolled-back group is not the owner's group");
});

test("the host's capacity answer is the gate: no room, no lane", async () => {
  const built = [];
  const port = hostPort();
  port.buildLane = async (lane) => {
    built.push(lane.laneId);
    return { laneId: lane.laneId };
  };
  delete port.createLane;
  const seen = [];
  const fission = new Fission({
    port,
    origins: (name) => name === "w1",
    // A real answer, not a constant: this host can take two more runtimes.
    capacity: (count) => {
      seen.push(count);
      return count <= 2;
    },
  });
  assert.deepEqual(await fission.fission("w1", ["a", "b", "c"]), { ok: false, rejection: "CapacityExceeded" });
  assert.deepEqual(seen, [3]);
  assert.deepEqual(built, [], "a refused group builds nothing");
  assert.equal((await fission.fission("w1", ["a", "b"])).ok, true);
});

test("a lane's settlement is its delivery, and the takeover lane's settlement converges the group", async () => {
  const port = hostPort();
  delete port.createLane;
  port.buildLane = async (lane) => ({ laneId: lane.laneId });
  const fission = fissionOf(port);
  const { groupId } = await fission.fission("w1", ["a", "b", "c"]);
  // A lane that is not the takeover lane delivers and stops.
  const first = fission.settle("w1", 0, "lane zero");
  assert.equal(first.groupId, groupId);
  assert.equal(first.lane, 0);
  assert.equal(first.delivered, true);
  assert.equal(first.converged, undefined);
  assert.equal(fission.stateOf("w1"), "replaced");
  // A repeated settlement of the same lane is the same fact.
  assert.equal(fission.settle("w1", 0, "lane zero").delivered, true);
  assert.equal(fission.settle("w1", 1, "lane one").ready, false);
  // The takeover lane (n−1) delivers last and announces (§4.2 step 10).
  const last = fission.settle("w1", 2, "lane two");
  assert.equal(last.ready, true);
  assert.equal(last.converged?.ok, true);
  assert.equal(last.converged?.result, "lane two");
  assert.equal(fission.stateOf("w1"), "converged");
  // A settlement that arrives after the terminal write is answered, not thrown.
  assert.equal(fission.settle("w1", 1, "late").terminal, "converged");
  // A name no open group owns is not a lane settlement at all.
  assert.equal(fission.settle("w2", 0, "x"), null);
});

// §4.2 steps 8–10 do not depend on the order lanes finish in.
//
// For ANY lane count, ANY order the lanes deliver in, and ANY interleaving of
// the deliveries with the pre-fission claims, the group converges exactly once,
// the announcement is made by the takeover lane (n−1), the announced result is
// that lane's own report — never the last arrival's — and the merge order stays
// 0..n−1.
test("whatever order the lanes and the broadcasts arrive in, the takeover lane announces once", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.integer({ min: 2, max: 6 }),
      // The order the deliveries arrive in.
      fc.constantFrom("forward", "backward", "shuffle"),
      // The seed of a deterministic shuffle, so a failure is reproducible.
      fc.nat({ max: 10_000 }),
      // How many pre-fission completions each lane still owes a claim for.
      fc.integer({ min: 0, max: 3 }),
      // Whether claims happen before or after the delivery of each lane.
      fc.boolean(),
      async (laneCount, order, seed, broadcastCount, claimFirst) => {
        const port = hostPort();
        delete port.createLane;
        port.buildLane = async (lane) => ({ laneId: lane.laneId });
        const fission = fissionOf(port);
        const prompts = Array.from({ length: laneCount }, (_, index) => `lane ${index}`);
        const { groupId } = await fission.fission("w1", prompts);
        const group = fission.groupOf("w1");

        // The completions captured before fission, each with its own key.
        const keys = Array.from({ length: broadcastCount }, (_, index) => `c${index}`);
        for (const key of keys) group.broadcast({ key, text: `done ${key}` });

        const laneOrder = [...Array(laneCount).keys()];
        if (order === "backward") laneOrder.reverse();
        if (order === "shuffle") {
          let state = seed + 1;
          for (let index = laneOrder.length - 1; index > 0; index -= 1) {
            state = (state * 1103515245 + 12345) % 2147483648;
            const swap = state % (index + 1);
            [laneOrder[index], laneOrder[swap]] = [laneOrder[swap], laneOrder[index]];
          }
        }

        const announcements = [];
        const reports = new Map();
        const claimAll = (lane) => {
          for (const key of keys) group.claimBroadcast(lane, key);
        };
        // Every fact goes through compose, and the announcement is read where it
        // happens, exactly as the hosts read it.
        const book = (receipt, lane) => {
          if (receipt !== null && receipt.converged !== undefined) announcements.push({ lane, receipt });
        };
        for (const lane of laneOrder) {
          const report = `report ${lane}`;
          reports.set(lane, report);
          if (claimFirst) claimAll(lane);
          book(fission.settle("w1", lane, report), lane);
          if (!claimFirst) claimAll(lane);
          // A claim can complete the conjunction too; compose answers that with
          // the same announcement, so ask again after the claims.
          book(fission.settle("w1", lane, report), lane);
        }

        // Exactly one announcement, by the takeover lane, of its own report.
        assert.equal(announcements.length, 1, `announcements by lanes ${JSON.stringify(announcements.map((a) => a.lane))}`);
        const [announcement] = announcements;
        // The announcement is booked against the TAKEOVER lane, whatever lane's
        // delivery completed the conjunction (`receipt.lane` is the arrival).
        assert.equal(announcement.receipt.converged.lanes.length, laneCount);
        assert.equal(group.phase, "converged");
        assert.equal(announcement.receipt.converged.ok, true);
        assert.equal(announcement.receipt.converged.result, reports.get(laneCount - 1), "the announced result is lane n−1's own report");
        assert.equal(fission.stateOf("w1"), "converged");
        // The merge order is topology, not arrival order.
        assert.deepEqual(
          announcement.receipt.converged.lanes.map((lane) => lane.index),
          Array.from({ length: laneCount }, (_, index) => index),
        );
        assert.equal(announcement.receipt.converged.groupId, groupId);
        // A settlement that arrives after the terminal write is answered, not
        // thrown, and it announces nothing.
        assert.equal(fission.settle("w1", 0, "late").terminal, "converged");
      },
    ),
  );
});

test("the takeover lane finishing first does not stall the group: the last sibling's delivery announces its report", async () => {
  // The regression fix-omp found: `settle` used to announce only on the takeover
  // lane's own call, so a group whose lane n−1 finished first stayed `ready`
  // forever. The last delivery now announces, with lane n−1's stored report.
  const port = hostPort();
  delete port.createLane;
  port.buildLane = async (lane) => ({ laneId: lane.laneId });
  const fission = fissionOf(port);
  await fission.fission("w1", ["a", "b", "c"]);
  const first = fission.settle("w1", 2, "takeover report");
  assert.equal(first.ready, false);
  assert.equal(first.converged, undefined);
  assert.equal(fission.stateOf("w1"), "replaced");
  const second = fission.settle("w1", 0, "lane zero");
  assert.equal(second.converged, undefined);
  const last = fission.settle("w1", 1, "lane one");
  assert.equal(last.ready, true);
  assert.equal(last.converged?.ok, true);
  assert.equal(last.converged.result, "takeover report", "the takeover lane's own report is announced, not the last arrival's");
  assert.equal(fission.stateOf("w1"), "converged");
});
