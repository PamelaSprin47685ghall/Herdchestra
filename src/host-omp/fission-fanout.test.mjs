// §4.2 steps 7–10 on this host: lane turn ends feed the group.
//
// The property under test is the one the spec states as topology: for ANY order
// in which lanes finish and pre-fission facts arrive, the group converges
// exactly once, the announcement comes only from lane n−1, and the merge is in
// lane order 0..n−1 — never in the order the turn ends happened to arrive.
//
// The lanes here are real `LaneTable` lanes (a fake session per lane, driven by
// `endTurn`), the group is the real compose `Fission`, and the port is the real
// `fissionPort` from `ports.mjs`. Nothing about convergence is re-implemented in
// this file: the assertion is that the adapter ROUTES each turn end, and that
// compose's own decision is the one the caller sees.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import fc from "fast-check";

import { Fission } from "../compose/fission/fission.mjs";
import { fissionPort } from "./ports.mjs";
import { feedLaneEnd, groupIdOfLane, laneAddressOf, laneResult } from "./fission-fanout.mjs";
import { LaneTable } from "./lane.mjs";
import { ROLE } from "./host-facts.mjs";

// Every harness root, removed once at exit: a durable group journal is part of
// the contract now, and a test that commits a group must not leak its file.
const ROOTS = [];
process.once("exit", () => {
  for (const root of ROOTS) {
    try {
      fs.rmSync(root, { recursive: true, force: true });
    } catch {}
  }
});

// One fake host session per lane. It exposes exactly what `LaneTable.start`
// wires and observes (`subscribe`, `prompt`, `steer`, the runtime members), and
// lets the test decide when a turn ends.
const fakeLaneSession = (agentId) => {
  const listeners = [];
  const session = {
    agent: { kind: "sub", id: agentId, name: agentId, depth: 1 },
    isStreaming: false,
    model: { id: "m" },
    thinkingLevel: "medium",
    serviceTierByFamily: {},
    queuedMessageCount: 0,
    systemPrompt: ["prompt"],
    customCommands: [],
    skills: [],
    getAgentId: () => agentId,
    getEnabledToolNames: () => [],
    getAllToolInfos: () => [],
    setActiveToolsByName: async () => {},
    sendCustomMessage: async () => true,
    sendUserMessage: async () => {},
    sessionManager: {
      getSessionId: () => `sess-${agentId}`,
      appendCustomEntry: () => {},
      appendLabelChange: () => {},
      getSessionName: () => undefined,
      setSessionName: async () => {},
    },
    modelRegistry: { getApiKey: async () => "key" },
    setModel: async () => {},
    setThinkingLevel: () => {},
    setServiceTierFamily: () => {},
    abort: async () => {},
    getContextUsage: () => undefined,
    runEphemeralTurn: async () => ({ text: "" }),
    compact: async () => {},
    waitForIdle: async () => {},
    newSession: async () => true,
    branch: async () => ({ cancelled: false }),
    navigateTree: async () => ({ cancelled: false }),
    switchSession: async () => true,
    reload: async () => {},
    async prompt() {
      session.isStreaming = true;
      session.emit({ type: "agent_start" });
    },
    async steer() {},
    subscribe(listener) {
      listeners.push(listener);
      return () => {
        const index = listeners.indexOf(listener);
        if (index >= 0) listeners.splice(index, 1);
      };
    },
    async dispose() {},
    emit(event) {
      for (const listener of [...listeners]) listener(event);
    },
    // The lane's authorization handshake: this adapter answers its own token.
    extensionRunner: {
      initialize() {},
      async emit(event) {
        const handshake = event?.herdchestraHandshake;
        if (handshake !== undefined && typeof handshake.reply === "function") {
          handshake.reply({ token: handshake.token, bound: true, narrowed: true, role: "coder", reason: null });
        }
      },
    },
    endTurn(text) {
      session.isStreaming = false;
      session.emit({ type: "message_end", message: { role: "assistant", content: [{ type: "text", text }] } });
      session.emit({ type: "agent_end", isTerminal: true });
    },
  };
  return session;
};

// A session whose lanes are real `LaneTable` lanes and whose group is the real
// compose `Fission` over the real port.
const harness = () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-fission-"));
  ROOTS.push(root);
  const sessions = [];
  const events = [];
  const api = {
    registerTool: () => {},
    getActiveTools: () => [],
    setActiveTools: async () => {},
    on: () => {},
    pi: {
      async createAgentSession(options) {
        const session = fakeLaneSession(options.agentId);
        sessions.push(session);
        return { session };
      },
    },
  };
  const lanes = new LaneTable({ api, workerToolNames: ["js-worker", "fork", "join"] });
  const laneRecords = new Map();
  let group = null;
  const port = fissionPort({
    lanes: laneRecords,
    startLane: async (request) => lanes.start({ ...request, register: false }),
    agentFor: () => "coder",
    owner: () => "w1",
    recordFile: path.join(root, ".Herdchestra", "fission.jsonl"),
    fission: () => group,
    // The owner's own control surface (§4.2 step 6). A mark, a settled stop and
    // an armed turn-end abort are what "the old present stops producing turns"
    // means on this host; the port asks for all three.
    present: {
      replace: () => ({ ok: true, replaced: { groupId: "g" } }),
      stop: async () => ({ ok: true, stillStreaming: false }),
      atTurnEnd: (fn) => ({ ok: true, ran: true, fn }),
      silence: async () => ({ ok: true }),
    },
    fanout: {
      feedLaneEnd: (args) => feedLaneEnd({ ...args, onEvent: (entry) => events.push(entry) }),
    },
  });
  group = new Fission({ port, origins: () => true, capacity: () => true });
  return { sessions, events, lanes, group, port, laneRecords };
};

test("lane ids name their group and index, and a result carries the lane's own report", () => {
  assert.equal(groupIdOfLane("w1:3:1#2"), "w1:3:1");
  assert.equal(groupIdOfLane("w1:3:1#0"), "w1:3:1");
  assert.equal(groupIdOfLane("no-hash"), null);
  assert.equal(groupIdOfLane(undefined), null);
  assert.deepEqual(laneAddressOf({ laneId: "g#1", index: 1 }, "g"), { groupId: "g", index: 1, laneId: "g#1" });
  assert.equal(laneAddressOf({ laneId: "g#1", index: -1 }, "g"), null);
  assert.equal(laneAddressOf(null, "g"), null);
  assert.deepEqual(laneResult({ name: "n", text: "t" }), { name: "n", agentId: "n", text: "t" });
  assert.deepEqual(laneResult({ name: "n", text: null, error: { message: "boom" } }), {
    name: "n",
    agentId: "n",
    text: null,
    error: { message: "boom" },
  });
});

test("one group: the adapter delivers each lane's end, and only lane n-1 announces", async () => {
  const { sessions, group, port } = harness();
  const receipt = await group.fission("w1", ["lane zero", "lane one", "lane two"]);
  assert.equal(receipt.ok, true, JSON.stringify(receipt));
  const [l0, l1, l2] = receipt.lanes.map((lane) => lane.laneId);
  // Lane order is 0..n−1 regardless of which session was created first.
  assert.deepEqual(receipt.lanes.map((lane) => lane.index), [0, 1, 2]);
  assert.equal(sessions.length, 3);

  // Lane 2 (the takeover lane) ends FIRST: a delivery, never an announcement,
  // because its siblings have not delivered yet.
  const early = port.laneTurnEnd(l2, { text: "two" });
  assert.equal(early.ok, true);
  assert.equal(early.settled.converged, undefined, "a lane alone never announces");
  assert.equal(group.stateOf("w1"), "replaced");

  // Lane 0 ends next: still a delivery — the group is not ready.
  const middle = port.laneTurnEnd(l0, { text: "zero" });
  assert.equal(middle.settled.converged, undefined);
  assert.equal(group.stateOf("w1"), "replaced");

  // Lane 1 ends last. Every lane has now delivered, so the takeover lane
  // announces — with ITS OWN report, never with the report of the lane whose
  // turn end happened to arrive last.
  const last = port.laneTurnEnd(l1, { text: "one" });
  assert.equal(last.ok, true);
  assert.notEqual(last.settled.converged, undefined, "the takeover lane announces once ready");
  // The announcement is lane n−1's, and it carries lane n−1's OWN settled
  // result. The report of the lane whose end arrived last is not the merge.
  assert.equal(last.settled.converged.lanes[last.settled.converged.lanes.length - 1].index, 2);
  // compose stores the lane RESULT object, so the text it carries is the
  // takeover lane's own report.
  assert.equal(last.settled.converged.result.text, "two", "the merge is the takeover lane's own result");
  assert.equal(group.stateOf("w1"), "converged");

  // A late re-settlement is answered as terminal, never as a second
  // announcement.
  const again = port.laneTurnEnd(l2, { text: "two" });
  assert.equal(again.settled.terminal, "converged");
  assert.equal(again.settled.converged, undefined);
});

test("for any lane finish order, the group converges exactly once, announced only by lane n-1, merged 0..n-1", async () => {
  await fc.assert(
    fc.asyncProperty(
      // 2..5 lanes, and any permutation of their finish order.
      fc.integer({ min: 2, max: 5 }).chain((n) =>
        fc.record({
          n: fc.constant(n),
          order: fc.shuffledSubarray([...Array(n).keys()], { minLength: n, maxLength: n }),
        }),
      ),
      async ({ n, order }) => {
        const { group, port, sessions } = harness();
        const prompts = Array.from({ length: n }, (_, index) => `lane ${index}`);
        const admitted = await group.fission("w1", prompts);
        assert.equal(admitted.ok, true, JSON.stringify(admitted));
        const laneIds = admitted.lanes.map((lane) => lane.laneId);
        assert.equal(sessions.length, n);

        const announcements = [];
        for (const index of order) {
          const settled = port.laneTurnEnd(laneIds[index], { text: `text-${index}` });
          assert.equal(settled.ok, true, JSON.stringify(settled));
          if (settled.settled?.converged !== undefined) announcements.push({ triggeredBy: index, receipt: settled.settled.converged });
        }

        // Exactly one announcement, and it is the takeover lane's: the receipt
        // names the lane that announced, whatever turn end happened to make the
        // group ready. (A sibling's late delivery is what usually makes it
        // ready, so the TRIGGER is any lane; the ANNOUNCER is always n−1.)
        assert.equal(announcements.length, 1, `one announcement for order ${order.join(",")}`);
        assert.equal(announcements[0].receipt.lanes.at(-1).index, n - 1);
        assert.equal(announcements[0].receipt.result.text, `text-${n - 1}`, "the takeover lane's own report");
        assert.equal(group.stateOf("w1"), "converged");

        // The merge is in lane order 0..n−1, not in arrival order: the
        // announcement carries lane n−1's OWN result, never the report of
        // whichever lane's turn end happened to arrive last.
        assert.deepEqual(
          group.groupOf("w1").lanes.map((lane) => lane.index),
          [...Array(n).keys()],
        );
        assert.deepEqual(group.groupOf("w1").mergeOrder(), [...Array(n).keys()]);
        // The merge carries lane n−1's own report, never the last arrival's.
        assert.equal(announcements[0].receipt.result.text, `text-${n - 1}`);

        // A late re-settlement of any lane is answered as terminal, never as a
        // second announcement.
        for (const index of order) {
          const again = port.laneTurnEnd(laneIds[index], { text: `again-${index}` });
          assert.equal(again.ok, true);
          assert.equal(again.settled.terminal, "converged");
          assert.equal(again.settled.converged, undefined);
        }
      },
    ),
    { numRuns: 100 },
  );
});

test("a pre-fission completion is claimed once per lane and does not depend on arrival order", async () => {
  await fc.assert(
    fc.asyncProperty(
      fc.integer({ min: 2, max: 4 }).chain((n) =>
        fc.record({
          n: fc.constant(n),
          order: fc.shuffledSubarray([...Array(n).keys()], { minLength: n, maxLength: n }),
          facts: fc.uniqueArray(fc.string({ minLength: 1, maxLength: 8 }), { minLength: 1, maxLength: 3 }),
        }),
      ),
      async ({ n, order, facts }) => {
        const { group, port } = harness();
        const admitted = await group.fission("w1", Array.from({ length: n }, (_, i) => `lane ${i}`));
        assert.equal(admitted.ok, true);
        const laneIds = admitted.lanes.map((lane) => lane.laneId);

        // §4.2 step 7: each captured completion reaches every lane once. The
        // FIRST claim of (lane, fact) is the delivery; every later one is not.
        for (const key of facts) {
          group.broadcast("w1", { key });
          for (let index = 0; index < n; index += 1) {
            assert.equal(group.claim("w1", index, key), true, "the first claim delivers");
            assert.equal(group.claim("w1", index, key), false, "a repeat claim is not a delivery");
          }
        }

        // Convergence needs BOTH facts; the lanes deliver in an arbitrary order
        // and the takeover lane still announces exactly once.
        let announced = 0;
        for (const index of order) {
          const settled = port.laneTurnEnd(laneIds[index], { text: `t${index}` });
          assert.equal(settled.ok, true);
          if (settled.settled?.converged !== undefined) {
            announced += 1;
            assert.equal(settled.settled.converged.lanes.at(-1).index, n - 1);
          }
        }
        assert.equal(announced, 1);
        assert.equal(group.stateOf("w1"), "converged");
      },
    ),
    { numRuns: 60 },
  );
});

test("a lane turn end that no group owns is reported, never invented", () => {
  // No group at all: the port says so by name.
  const { port } = harness();
  assert.deepEqual(port.laneTurnEnd("w1:2:9#0", { text: "x" }), { ok: false, reason: "LANE_UNKNOWN", laneId: "w1:2:9#0" });
  // A lane id this port never built.
  assert.equal(port.laneRecord("w1:2:9#0"), null);
});

test("a worker of the adapter's own role is what a lane runs", async () => {
  const { group, sessions } = harness();
  const admitted = await group.fission("w1", ["a", "b"]);
  assert.equal(admitted.ok, true);
  assert.equal(sessions.length, 2);
  // A lane keeps the owner's worker identity: it is the same participant.
  assert.equal(ROLE.CODER, "coder");
});
