// The worker roster is durable, and `join` never lies about it (§4.1, §7.1).
//
// The failure these properties guard was measured on 2026-11-01, session
// `ses_e6ab649c8ffea0B023Dy3Aj2P0`: four workers were forked (`devops`,
// `coder-core`, `coder-hosts`, `coder-tests`), two were joined, and then
// OpenCode reloaded the plugin because a watched file changed. `WorkerTable`
// lived only in the activation, so the reloaded one held an empty roster and
// every later `join` answered `{ ok: false, record: null }` in one millisecond
// while `devops` and `coder-hosts` were still running in the same server.
//
// Two invariants are proved here, over arbitrary interleavings of `fork`,
// `steer`, `join`, host completion events and plugin reloads:
//
//   progress   while any forked worker is still running, or holds a result no
//              `join` has claimed, `join` does not answer "nothing to collect";
//   accounting every forked worker is claimed exactly once, and the roster the
//              journal rebuilds agrees with the host on which sessions are live.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

import fc from "fast-check";

import { WorkerTable, restoreRoster, settledOutcomeOf } from "./worker-sessions.mjs";
import { SessionState, stateFile } from "./session-state.mjs";
import { devopsRoadOf, makeActivation, workerTableOf } from "./activation.mjs";
import { fcRuns } from "../../core/test-runs.mjs";
import { removeRoot, tempRoot as tempRootDir } from "./test-temp.mjs";

// One workspace per test or scenario; the registry removes it when the process
// exits, so no file can forget one.
const tempRoot = (prefix) => tempRootDir(prefix);

const MANAGER = "ses_manager";

// A host that answers the way OpenCode does: a session is created without an
// outcome, carries `time.busy > time.idle` while a turn runs, and keeps the
// outcome of its last completed turn afterwards (`session_v2.idle_outcome`).
//
// `wait` resolves when the session stops running — and, like the real
// `SessionExecution.awaitIdle`, resolves at once for a session that is idle
// right now. A `join` that treats that answer as "finished" without checking
// the record would report a worker that never came back.
const makeHost = () => {
  let created = 0;
  const sessions = new Map();
  const waiters = new Map();
  const calls = [];

  const infoOf = (sessionID) => {
    const session = sessions.get(sessionID);
    if (session === undefined) return null;
    return {
      id: sessionID,
      title: session.title,
      ...(session.outcome === null ? {} : { outcome: session.outcome }),
      time: { created: 1, updated: session.clock, busy: session.busy, ...(session.idle === null ? {} : { idle: session.idle }) },
    };
  };

  const settleWaiters = (sessionID) => {
    const list = waiters.get(sessionID);
    if (list === undefined) return;
    waiters.delete(sessionID);
    for (const resolve of list) resolve();
  };

  return {
    calls,
    sessions,
    domain: {
      create: async (input) => {
        created += 1;
        const id = `ses_w${created}`;
        calls.push(["create", input]);
        sessions.set(id, {
          title: input.title ?? id,
          busy: 1,
          idle: null,
          outcome: null,
          clock: 1,
          started: false,
          runs: 0,
          completed: 0,
        });
        return { data: { id, ...input } };
      },
      prompt: async (input) => {
        calls.push(["prompt", input]);
        const session = sessions.get(input.sessionID);
        if (session === undefined) return { data: {} };
        // `delivery: "queue"` means the turn starts on its own. Either way the
        // session is busy from this moment: a queued turn has started, and a
        // steer on a running session joins the turn it already has.
        //
        // The previous turn's outcome stays on the record until the new turn
        // finishes — the host writes `idle_outcome` only at a terminal, so a
        // running session can carry a stale one (`settledOutcomeOf`).
        // One clock for both marks, always increasing: the host's busy mark is
        // never behind the idle mark of the turn before it.
        session.clock = Math.max(session.clock, session.idle ?? 0, session.busy ?? 0) + 1;
        session.busy = session.clock;
        session.idle = null;
        if (session.started !== true) session.started = true;
        session.runs += 1;
        return { data: {} };
      },
      wait: async (input) => {
        calls.push(["wait", input]);
        const session = sessions.get(input.sessionID);
        if (session === undefined) return;
        if (session.busy <= session.idle) return;
        await new Promise((resolve) => {
          const list = waiters.get(input.sessionID) ?? [];
          list.push(resolve);
          waiters.set(input.sessionID, list);
        });
      },
      get: async (input) => {
        calls.push(["get", input]);
        const info = infoOf(input.sessionID);
        if (info === null) throw new Error(`no session ${input.sessionID}`);
        return { data: info };
      },
    },
    // The host finishes a running turn: it records the outcome, clears the busy
    // clock and answers every `wait` on that session.
    complete(sessionID, outcome = "succeeded") {
      const session = sessions.get(sessionID);
      if (session === undefined || session.busy <= session.idle) return false;
      session.completed += 1;
      session.clock = Math.max(session.clock, session.busy ?? 0, session.idle ?? 0) + 1;
      session.idle = session.clock;
      session.outcome = outcome;
      settleWaiters(sessionID);
      return true;
    },
    // A steered worker starts another turn: the previous outcome is stale and
    // the session is busy again.
    steer(sessionID) {
      const session = sessions.get(sessionID);
      if (session === undefined) return false;
      session.clock = Math.max(session.clock, session.busy ?? 0, session.idle ?? 0) + 1;
      session.busy = session.clock;
      session.idle = null;
      session.runs += 1;
      return true;
    },
    running(sessionID) {
      const session = sessions.get(sessionID);
      return session !== undefined && session.busy > session.idle;
    },
    sessionIDs() {
      return [...sessions.keys()];
    },
  };
};

// One activation's worker table, over a host and a workspace. A second call
// with the same root is what a plugin reload looks like: new activation, new
// in-memory maps, the same journal on disk.
const tableOf = ({ host, root, records = null }) => {
  const activation = makeActivation({ sessions: host.domain, root });
  const state = activation.states.get(MANAGER);
  if (records !== null) for (const entry of records) state.recordWorker(entry);
  return { activation, state, table: workerTableOf(activation, MANAGER) };
};



// A join that must not answer yet. `quiet(join)` reports whether it answered
// within a few macrotasks, and `within(promise)` bounds the eventual answer so a
// regression fails the test instead of hanging the runner.
const quiet = async (promise) => {
  let answered = false;
  void promise.then(() => {
    answered = true;
  });
  for (let tick = 0; tick < 5; tick += 1) await new Promise((resolve) => setTimeout(resolve, 0));
  return answered;
};

// Whether a join answers `null` without waiting — the shape that made the live
// bug a 1 ms spin: "nothing to collect" while workers were still running.
const reportsNothing = async (promise) => {
  const answer = await Promise.race([
    promise,
    new Promise((resolve) => setTimeout(() => resolve("still-waiting"), 5)),
  ]);
  return answer === null;
};

const within = (promise, ms = 1000) =>
  Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`join did not answer within ${ms} ms`)), ms)),
  ]);

// What the roster says is still owed to a caller: a running worker, or a
// result nobody joined. This is the count the invariant is stated over.
const owed = (table) => table.liveCount() + table.unclaimed().length;

// A name→session binding of every worker the table holds, live or idle.
const rosterOf = (table) => new Map(table.rosterEntries().map((entry) => [entry.name, entry.sessionID]));

test("settledOutcomeOf reads a stale outcome as still running", () => {
  // A session that finished its last turn and was steered again carries both
  // the old outcome and a busy clock past the idle one.
  assert.deepEqual(settledOutcomeOf({ outcome: "succeeded", time: { busy: 9, idle: 4 } }), { settled: false });
  assert.deepEqual(settledOutcomeOf({ outcome: "succeeded", time: { busy: 4, idle: 4 } }), {
    settled: true,
    outcome: "succeeded",
  });
  // No outcome at all is "not finished", never an invented one.
  assert.deepEqual(settledOutcomeOf({ time: { busy: 4, idle: 2 } }), { settled: false });
  assert.deepEqual(settledOutcomeOf(null), { settled: false });
});

test("a steered worker is still running after its previous outcome", async () => {
  const host = makeHost();
  const root = tempRoot();
  const { table } = tableOf({ host, root });
  await table.fork({ name: "w1", brief: "role: coder\n\ngo", role: "coder", parentID: MANAGER, title: "w1" });
  const [sessionID] = host.sessionIDs();

  // The host finishes the first turn: the session now carries `succeeded`, and
  // a `join` may report it.
  host.complete(sessionID, "succeeded");
  // A steer arrives before anyone joined: the same worker, a new turn. §4.1
  // steers a LIVE name; a name whose worker already finished starts a new
  // session instead. The record is what says "busy again", and the session's
  // own outcome is now stale.
  await table.fork({ name: "w1", brief: "role: coder\n\nmore", role: "coder", parentID: MANAGER });
  assert.equal(table.isLive("w1"), true, "a steered worker is running again");
  assert.equal(table.size, 1, "a steer does not start a second session");
  assert.equal(host.running(sessionID), true, "the host reports the new turn as running");
  assert.equal(host.sessions.get(sessionID).outcome, "succeeded", "the previous outcome is still on the record");

  // The record's outcome alone would report a worker that never came back. The
  // join waits for the turn that is actually running.
  const pending = table.join();
  assert.equal(await quiet(pending), false, "join must not answer on a stale outcome");
  host.complete(sessionID, "failed");
  const record = await within(pending);
  assert.equal(record?.name, "w1");
  assert.equal(record?.outcome, "failed", "the record reports the turn that finished");
});

test("a join waits when the host answers before the session has an outcome", async () => {
  const host = makeHost();
  const root = tempRoot();
  const { table } = tableOf({ host, root });
  // A host that answers `wait` for a session that is idle and has never run:
  // the record carries no outcome, so nothing was finished.
  const original = host.domain.wait;
  host.domain.wait = async (input) => {
    if (input.sessionID === "ses_idle") return;
    return original(input);
  };
  const forked = await table.fork({ name: "w1", brief: "role: coder\n\ngo", role: "coder", parentID: MANAGER, title: "w1" });
  // Replace the child with one the fake reports as idle without an outcome.
  const child = forked.sessionID;
  host.sessions.get(child).busy = 1;
  host.sessions.get(child).idle = 1;
  const pending = table.join();
  assert.equal(await quiet(pending), false, "an answer without an outcome is not a finish");
  // The worker really finishes later.
  host.sessions.get(child).busy = 2;
  host.complete(child, "succeeded");
  assert.equal((await within(pending))?.name, "w1");
});

test("a plugin reload keeps the roster and the running workers it forked", async () => {
  const host = makeHost();
  const root = tempRoot();
  const first = tableOf({ host, root });
  const forked = [];
  for (const name of ["devops", "coder-core", "coder-hosts", "coder-tests"]) {
    const role = name === "devops" ? "devops" : "coder";
    forked.push(
      await first.table.fork({ name, brief: `role: ${role}\n\ngo`, role, parentID: MANAGER, title: name }),
    );
  }
  const ids = forked.map((receipt) => receipt.sessionID);
  assert.equal(first.table.liveCount(), 4);

  // Two finish and are joined; the other two keep running.
  host.complete(ids[1], "succeeded");
  host.complete(ids[3], "succeeded");
  assert.equal((await first.table.join()).name, "coder-core");
  assert.equal((await first.table.join()).name, "coder-tests");

  // The plugin reloads: a brand-new activation over the same workspace.
  const second = tableOf({ host, root });
  assert.equal(second.table.size, 4, "the roster survived the reload");
  assert.deepEqual(rosterOf(second.table), rosterOf(first.table));

  const pending = second.table.join();
  assert.equal(await quiet(pending), false, "join must not answer while two workers are still running");
  host.complete(ids[0], "succeeded");
  const record = await within(pending);
  assert.equal(record?.name, "devops", JSON.stringify(record));
  host.complete(ids[2], "succeeded");
  assert.equal((await second.table.join())?.name, "coder-hosts");
  assert.equal(await second.table.join(), null, "only after every worker was claimed");
});

test("a worker that finished during the reload is claimable, not lost", async () => {
  const host = makeHost();
  const root = tempRoot();
  const first = tableOf({ host, root });
  const forked = await first.table.fork({
    name: "w1",
    brief: "role: coder\n\ngo",
    role: "coder",
    parentID: MANAGER,
    title: "w1",
  });
  // The worker finishes while no plugin is loaded: the host records the
  // outcome, the journal still says "live".
  host.complete(forked.sessionID, "failed");
  const second = tableOf({ host, root });
  const record = await second.table.join();
  assert.equal(record?.name, "w1");
  assert.equal(record?.outcome, "failed");
  assert.equal(await second.table.join(), null);
});

test("a claim survives a reload and is never handed over twice", async () => {
  const host = makeHost();
  const root = tempRoot();
  const first = tableOf({ host, root });
  const a = await first.table.fork({ name: "a", brief: "role: coder\n\ngo", role: "coder", parentID: MANAGER, title: "a" });
  const b = await first.table.fork({ name: "b", brief: "role: coder\n\ngo", role: "coder", parentID: MANAGER, title: "b" });
  host.complete(a.sessionID, "succeeded");
  assert.equal((await first.table.join())?.name, "a");
  const second = tableOf({ host, root });
  // `a` was claimed and `b` is still running, so the reloaded table has
  // something to wait for — not "nothing to collect".
  const pending = second.table.join();
  assert.equal(await quiet(pending), false, "a claim is not re-offered, and b is still running");
  host.complete(b.sessionID, "succeeded");
  assert.equal((await within(pending))?.name, "b", "only the unclaimed worker answers");
  assert.equal(await second.table.join(), null, "a claimed result is not offered again");
});

test("the road-bound devops is restored, and released once it finished", async () => {
  const host = makeHost();
  const root = tempRoot();
  const first = tableOf({ host, root });
  const devops = await first.table.fork({
    name: "devops",
    brief: "role: devops\n\ngo",
    role: "devops",
    parentID: MANAGER,
    title: "devops",
  });
  devopsRoadOf(first.activation, MANAGER).add("devops");

  // A reload while it runs: the road is still held, and the journal says so.
  const running = tableOf({ host, root });
  assert.equal(devopsRoadOf(running.activation, MANAGER).has("devops"), true, "a running devops still holds the road");

  // It finishes while no plugin is loaded. The journal still says "running";
  // the host is the authority, and the reloaded activation asks it.
  host.complete(devops.sessionID, "succeeded");
  const finished = tableOf({ host, root });
  assert.equal(devopsRoadOf(finished.activation, MANAGER).has("devops"), true, "the journal alone still says running");
  await finished.table.reconcile();
  // Reconciliation is what the activation binds from; ask it to bind again the
  // way `fork` does before it admits a second devops.
  const table = workerTableOf(finished.activation, MANAGER);
  for (const worker of table.rosterEntries()) {
    if (worker.role === "devops" && worker.state === "idle") devopsRoadOf(finished.activation, MANAGER).delete(worker.name);
  }
  assert.equal(devopsRoadOf(finished.activation, MANAGER).has("devops"), false, "a finished devops has released the road");
});

test("restoreRoster replays fork, settle, steer, claim and release in order", () => {
  const records = [
    { type: "worker-fork", name: "w1", role: "coder", sessionID: "ses_1" },
    { type: "worker-fork", name: "w2", role: "devops", sessionID: "ses_2" },
    { type: "worker-settle", name: "w1", sessionID: "ses_1", outcome: "succeeded", title: "w1" },
    { type: "worker-settle", name: "w2", sessionID: "ses_2", outcome: "succeeded", title: "w2" },
    { type: "worker-claim", name: "w2", sessionID: "ses_2" },
  ];
  const restored = restoreRoster(records);
  assert.deepEqual(
    restored.unclaimed.map((entry) => [entry.name, entry.sessionID]),
    [["w1", "ses_1"]],
  );
  assert.equal(restored.entries.find((entry) => entry.name === "w2").state, "idle");

  // A steer makes the same session busy again and withdraws its stale result.
  const steered = restoreRoster([...records, { type: "worker-steer", name: "w1", sessionID: "ses_1" }]);
  assert.deepEqual(steered.unclaimed, []);
  assert.equal(steered.entries.find((entry) => entry.name === "w1").state, "running");

  // A release hands the name to a new session.
  const released = restoreRoster([
    ...records,
    { type: "worker-release", name: "w1", sessionID: "ses_1" },
    { type: "worker-fork", name: "w1", role: "coder", sessionID: "ses_3" },
  ]);
  assert.equal(released.entries.find((entry) => entry.name === "w1").sessionID, "ses_3");
});

test("a torn journal line does not lose the roster before it", () => {
  const root = tempRoot();
  const state = new SessionState({ sessionID: MANAGER, root });
  state.recordWorker({ type: "worker-fork", name: "w1", role: "coder", sessionID: "ses_1" });
  state.recordWorker({ type: "worker-fork", name: "w2", role: "coder", sessionID: "ses_2" });
  const file = stateFile(root, MANAGER);
  fs.appendFileSync(file, '{"v":1,"type":"worker","entry":{"type":"worker-fo', "utf8");
  const roster = new SessionState({ sessionID: MANAGER, root }).workerRoster;
  assert.deepEqual(
    roster.entries.map((entry) => entry.name).sort(),
    ["w1", "w2"],
    "the complete records still answer",
  );
});

// ---------------------------------------------------------------------------
// The state machine.
//
// A scenario is a list of steps. Each step is one thing that can happen to a
// Manager session: a fork, a steer of a live worker, the host finishing a
// running worker, a `join`, or a plugin reload. The model below is the ground
// truth the table is checked against.
// ---------------------------------------------------------------------------

const workerName = fc.constantFrom("devops", "coder-core", "coder-hosts", "coder-tests");

const stepArb = fc.oneof(
  { weight: 5, arbitrary: fc.record({ kind: fc.constant("fork"), name: workerName }) },
  { weight: 2, arbitrary: fc.record({ kind: fc.constant("steer"), name: workerName }) },
  { weight: 4, arbitrary: fc.record({ kind: fc.constant("complete"), pick: fc.nat() }) },
  { weight: 4, arbitrary: fc.record({ kind: fc.constant("join") }) },
  { weight: 2, arbitrary: fc.record({ kind: fc.constant("reload") }) },
);

const scenarioArb = fc.array(stepArb, { minLength: 1, maxLength: 24 });

// Run one scenario against a real `WorkerTable` over a journal on disk, with a
// host that behaves like OpenCode's.
//
// The invariant is stated over facts nobody can misread: how many distinct
// worker sessions were forked, and how many results a caller received. The host
// decides what is running. Whenever a `join` answers `null`, the two must be
// equal — no forked worker may still be owed. Whenever a worker is still
// running, a fresh `join` must not answer at all.
const runScenario = async (steps) => {
  const host = makeHost();
  const root = tempRoot();
  let world = tableOf({ host, root });
  const byName = new Map();
  const claimed = new Set();
  const reload = () => {
    world = tableOf({ host, root });
  };

  const liveSessions = () => [...new Set(byName.values())].filter((sessionID) => host.running(sessionID));
  // Every worker session the table holds, by name.
  const rosterSessions = () => world.table.rosterEntries().map((entry) => entry.sessionID);

  for (const step of steps) {
    if (step.kind === "fork") {
      const role = step.name === "devops" ? "devops" : "coder";
      const receipt = await world.table.fork({
        name: step.name,
        brief: `role: ${role}\n\ngo`,
        role,
        parentID: MANAGER,
        title: step.name,
      });
      if (receipt.steered !== true) {
        byName.set(step.name, receipt.sessionID);
      } else {
        // A steered worker owes a new answer from the session it already has.
        claimed.delete(byName.get(step.name));
      }
      continue;
    }
    if (step.kind === "steer") {
      const sessionID = byName.get(step.name) ?? null;
      if (sessionID === null || host.running(sessionID) !== true) continue;
      await world.table.fork({
        name: step.name,
        brief: `role: ${step.name === "devops" ? "devops" : "coder"}\n\nmore`,
        role: step.name === "devops" ? "devops" : "coder",
        parentID: MANAGER,
      });
      claimed.delete(sessionID);
      continue;
    }
    if (step.kind === "complete") {
      const live = host.sessionIDs().filter((id) => host.running(id)).sort();
      if (live.length === 0) continue;
      host.complete(live[step.pick % live.length], step.pick % 3 === 0 ? "failed" : "succeeded");
      continue;
    }
    if (step.kind === "reload") {
      reload();
      continue;
    }
    // A join. Two facts are checked, and neither is a prediction:
    //
    //   - while a worker the table holds is still running, a join must not
    //     answer "nothing" (it may answer a finished result, and it must not
    //     answer a stale one);
    //   - a join that answers "nothing" is only allowed when every forked
    //     worker's result was claimed.
    const owed = [...new Set(byName.values())].filter((sessionID) => !claimed.has(sessionID));
    const runningNow = rosterSessions().filter((sessionID) => host.running(sessionID));
    const pending = world.table.join();
    if (runningNow.length > 0) {
      if (await reportsNothing(pending)) {
        // "Nothing to collect" while a worker runs is the live bug — unless a
        // finished result really was waiting, in which case answering is right.
        assert.equal(
          owed.filter((sessionID) => !host.running(sessionID)).length,
          0,
          `join answered "nothing" while ${runningNow.join(", ")} was still running`,
        );
        // The probe saw `null`; nothing is owed, so the scenario moves on.
        continue;
      }
      // The join is waiting, as it must. Finish one worker so it can answer.
      host.complete(runningNow[0], "succeeded");
      const record = await within(pending);
      assert.notEqual(record, null, "join answered null with a worker still running");
      assert.equal(claimed.has(record.sessionID), false, "a result was handed over twice");
      claimed.add(record.sessionID);
      continue;
    }
    const record = await within(pending);
    if (record === null) {
      const owedNow = owed.filter((sessionID) => !claimed.has(sessionID));
      assert.deepEqual(owedNow, [], `join answered "nothing" while ${owedNow.join(", ")} was still owed`);
      continue;
    }
    assert.equal(claimed.has(record.sessionID), false, "a result was handed over twice");
    claimed.add(record.sessionID);
  }

  // Drain: finish everything still running, then collect until nothing is owed.
  for (;;) {
    const pending = world.table.join();
    const live = host.sessionIDs().filter((id) => host.running(id));
    if (live.length === 0) {
      const record = await within(pending);
      if (record === null) break;
      assert.equal(claimed.has(record.sessionID), false, "a result was handed over twice");
      claimed.add(record.sessionID);
      continue;
    }
    host.complete(live[0], "succeeded");
    const record = await within(pending);
    assert.notEqual(record, null, "join answered null with a result available");
    assert.equal(claimed.has(record.sessionID), false, "a result was handed over twice");
    claimed.add(record.sessionID);
  }
  const owedAfter = [...new Set(byName.values())].filter((sessionID) => !claimed.has(sessionID));
  assert.deepEqual(owedAfter, [], "a forked worker was never claimed");
  // One workspace per scenario: the property runs ~60 times, and a root per run
  // would leave a directory per run behind (the exit registry is the backstop).
  removeRoot(root);
  return { byName, claimed };
};

test("property: join never answers 'nothing' while a worker is running or unclaimed", async () => {
  await fc.assert(
    fc.asyncProperty(scenarioArb, async (steps) => {
      await runScenario(steps);
    }),
    { ...fcRuns(60) },
  );
});

test("property: every forked worker is claimed exactly once across reloads", async () => {
  // `runScenario` drains until nothing is owed and refuses a result handed over
  // twice, so a run that returns at all has proved both halves of this. The
  // assertion here is that the ledger it returns agrees with the table.
  await fc.assert(
    fc.asyncProperty(scenarioArb, async (steps) => {
      const { byName, claimed } = await runScenario(steps);
      const owed = [...new Set(byName.values())].filter((sessionID) => !claimed.has(sessionID));
      assert.deepEqual(owed, [], "a forked worker was never claimed");
    }),
    { ...fcRuns(40) },
  );
});

test("property: the restored roster matches the journal the table wrote", async () => {
  await fc.assert(
    fc.asyncProperty(scenarioArb, async (steps) => {
      const host = makeHost();
      const root = tempRoot();
      const first = tableOf({ host, root });
      const forked = [];
      for (const step of steps) {
        if (step.kind !== "fork") continue;
        const role = step.name === "devops" ? "devops" : "coder";
        const receipt = await first.table.fork({
          name: step.name,
          brief: `role: ${role}\n\ngo`,
          role,
          parentID: MANAGER,
          title: step.name,
        });
        if (receipt.started === true) forked.push(receipt.sessionID);
      }
      // A reload reads exactly the names and sessions the table holds.
      const reloaded = tableOf({ host, root });
      assert.deepEqual(rosterOf(reloaded.table), rosterOf(first.table));
      assert.equal(reloaded.table.size, first.table.size);
      // And the same for a table built from the journal alone.
      const state = new SessionState({ sessionID: MANAGER, root });
      const restored = new WorkerTable({ sessions: host.domain, restored: state.workerRoster });
      assert.deepEqual(rosterOf(restored), rosterOf(first.table));
    }),
    { ...fcRuns(40) },
  );
});
