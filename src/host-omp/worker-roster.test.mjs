// The worker roster is durable, and `join` never lies about it (§4.1, §7.3).
//
// The failure these properties guard was measured on the OpenCode sibling on
// 2026-11-01 (session `ses_e6ab649c8ffea0B023Dy3Aj2P0`): four workers were
// forked, two were joined, the plugin reloaded because a watched file changed,
// and every later `join` answered "nothing to collect" in one millisecond while
// two workers were still running in the same server. OMP's roster has the same
// shape — a `LaneTable` in one process, workers that are real sessions — so the
// same lie was one reload away.
//
// Two invariants are proved here, over arbitrary interleavings of `fork`,
// `steer`, out-of-order completion, `join` and a reload:
//
//   progress      while a forked worker is still running, or holds a result no
//                 `join` claimed, `join` does not answer "nothing to join";
//   accounting    every forked worker is claimed exactly once, and a roster
//                 rebuilt from the journal agrees with what the workers did.
//
// Run with: `node --test src/host-omp/worker-roster.test.mjs`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import fc from "fast-check";

import { LaneTable } from "./lane.mjs";
import { TaskPort } from "./worker-port.mjs";
import { WorkerRoster, workerRosterFile, replayRoster, WORKER_RECORD_TYPES } from "./worker-roster.mjs";

// A session shaped like one the SDK returns. It records what the adapter asked
// it to do and lets the test decide when a turn ends: exactly the surface
// `fork`, `join` and `steer` use (prompt, steer, subscribe, getAgentId,
// dispose, extensionRunner).
const fakeSession = ({ agentId }) => {
  const calls = [];
  const listeners = [];
  const session = {
    calls,
    isStreaming: false,
    getAgentId: () => agentId,
    sendCustomMessage: async () => true,
    sendUserMessage: async () => {},
    sessionManager: {
      appendCustomEntry: () => {},
      appendLabelChange: () => {},
      getSessionName: () => "worker",
      setSessionName: async () => {},
    },
    getEnabledToolNames: () => [],
    getAllToolInfos: () => [],
    setActiveToolsByName: async () => {},
    modelRegistry: { getApiKey: async () => "key" },
    setModel: async () => {},
    thinkingLevel: "medium",
    setThinkingLevel: () => {},
    serviceTierByFamily: {},
    setServiceTierFamily: () => {},
    model: { id: "test/model" },
    abort: async () => {
      session.isStreaming = false;
    },
    queuedMessageCount: 0,
    getContextUsage: () => ({ tokens: 0, contextWindow: 1 }),
    systemPrompt: [],
    runEphemeralTurn: async () => ({ text: "" }),
    compact: async () => {},
    waitForIdle: async () => {},
    newSession: async () => true,
    branch: async () => ({ cancelled: false }),
    navigateTree: async () => ({ cancelled: false }),
    switchSession: async () => true,
    reload: async () => {},
    async prompt(text) {
      calls.push(["prompt", text]);
      session.isStreaming = true;
      session.emit({ type: "agent_start" });
      return true;
    },
    async steer(text) {
      calls.push(["steer", text]);
      return undefined;
    },
    subscribe(listener) {
      listeners.push(listener);
      return () => {
        const index = listeners.indexOf(listener);
        if (index >= 0) listeners.splice(index, 1);
      };
    },
    async dispose() {
      calls.push(["dispose"]);
    },
    // A runner whose adapter bound and narrowed: this adapter answers the lane's
    // authorization handshake with its own facts.
    extensionRunner: {
      initialize() {},
      async emit(event) {
        const handshake = event?.herdchestraHandshake;
        if (handshake !== undefined && typeof handshake.reply === "function") {
          handshake.reply({ token: handshake.token, bound: true, narrowed: true, role: "coder", reason: null });
        }
      },
    },
    emit(event) {
      for (const listener of [...listeners]) listener(event);
    },
    endTurn(text) {
      session.isStreaming = false;
      session.emit({ type: "message_end", message: { role: "assistant", content: [{ type: "text", text }] } });
      session.emit({ type: "agent_end", isTerminal: true });
    },
  };
  return session;
};

// The SDK the host injects as `api.pi`. Each start takes the next scripted
// session; `created` says which sessions the adapter asked for.
const fakeApi = () => {
  const created = [];
  const api = {
    created,
    pi: {
      registerTool: undefined,
      async createAgentSession(options) {
        created.push(options);
        return { session: fakeSession({ agentId: options.agentId ?? `worker-${created.length}` }) };
      },
    },
  };
  return api;
};

// Every root this file creates is removed once at exit. The property tests
// create one per scenario (~120 per run), so per-test cleanup is impractical;
// a process-exit sweep still leaves nothing behind.
const ROOTS = [];
process.once("exit", () => {
  for (const root of ROOTS) {
    try {
      fs.rmSync(root, { recursive: true, force: true });
    } catch {}
  }
});
const tempRoot = () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "herd-omp-roster-"));
  ROOTS.push(root);
  return root;
};

// One session's roster over a journal on disk. A second call with the same root
// and session id is what a reload looks like: a new in-memory table, the same
// journal. `running(name)` is the host's answer to "is this worker still running
// in this process?" — the surface's `#laneSessions` in the real adapter.
const world = ({ root, sessionId, running = () => null }) => {
  const api = fakeApi();
  const roster = new WorkerRoster({ file: workerRosterFile(root, sessionId) });
  const lanes = new LaneTable({ api, roster, runningSession: running });
  const sessions = new Map();
  const port = new TaskPort({ lanes, sleep: async () => {}, agentFor: () => "coder", sessions });
  return { api, roster, lanes, port, sessions };
};

// A join that must not answer yet. `quiet` reports whether it answered within a
// few macrotasks; `within` bounds the eventual answer so a regression fails the
// test instead of hanging the runner.
const quiet = async (promise) => {
  let answered = false;
  void promise.then(() => {
    answered = true;
  });
  for (let tick = 0; tick < 5; tick += 1) await new Promise((resolve) => setTimeout(resolve, 0));
  return answered;
};

const within = (promise, ms = 2000) =>
  Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`join did not answer within ${ms} ms`)), ms)),
  ]);

test("a join with a worker in flight waits, and answers that worker's own report", async () => {
  const root = tempRoot();
  const w = world({ root, sessionId: "sess-one" });
  const forked = await w.port.fork("w1", "role: coder — go");
  assert.deepEqual(forked, { name: "w1", steered: false, started: true });
  const session = w.lanes.live("w1").session;
  const joining = w.port.join();
  assert.equal(await quiet(joining), false, "join answered while its only worker was running");
  session.endTurn("the report");
  const report = await within(joining);
  assert.equal(report.ok, true);
  assert.equal(report.name, "w1");
  assert.equal(report.text, "the report");
});

test("a join after a reload returns the result the previous process recorded", async () => {
  // The exact failure this journal exists for: the worker finished while nobody
  // was listening, and the reloaded session must still owe that result.
  const root = tempRoot();
  const first = world({ root, sessionId: "sess-reload" });
  await first.port.fork("w1", "role: coder — go");
  const session = first.lanes.live("w1").session;
  session.endTurn("recorded while away");
  // No await: the settle reaches the journal synchronously, inside the host's
  // own emit. A reload on the very next line must already see it — that is the
  // race a process death after the turn would otherwise lose.
  const second = world({ root, sessionId: "sess-reload" });
  assert.deepEqual(second.lanes.names(), ["w1"], "the reloaded roster knows the worker");
  const report = await within(second.port.join());
  assert.equal(report.ok, true);
  assert.equal(report.name, "w1");
  // The turn's own answer travels with the settle record, so the reloaded
  // session hands over the SAME result instead of a name with no words in it.
  assert.equal(report.text, "recorded while away");
  assert.equal(report.restored, true);
  // Claimed once. The next join has nothing left, and says which name it holds.
  const again = await within(second.port.join());
  assert.equal(again.ok, false);
  assert.equal(again.reason, "NOTHING_TO_JOIN");
  assert.deepEqual(again.roster, ["w1"]);
});

test("a worker still running across an in-process reload keeps join waiting until it settles", async () => {
  const root = tempRoot();
  const first = world({ root, sessionId: "sess-running" });
  await first.port.fork("w1", "role: coder — long job");
  // No turn end: the worker is running when the reload happens.
  const second = world({
    root,
    sessionId: "sess-running",
    running: (name) => first.sessions.get(name) ?? null,
  });
  assert.deepEqual(second.lanes.names(), ["w1"]);
  assert.notEqual(second.lanes.running("w1"), null, "the rebuilt table adopted the live lane");
  const joining = second.port.join();
  assert.equal(await quiet(joining), false, "an adopted running worker must keep join waiting");
  // The worker's own session reports the turn's end; the adopted lane hears it.
  first.sessions.get("w1").endTurn("finished late");
  const report = await within(joining);
  assert.equal(report.name, "w1");
  assert.equal(report.text, "finished late");
});

test("a journal RUNNING entry with no live session is reported lost instead of spinning join", async () => {
  // A cross-process reload cannot drive the previous process's session. Leaving
  // the entry RUNNING made `join` wait for a turn end that will never arrive;
  // the lost fact is a result the caller can collect.
  const root = tempRoot();
  const first = world({ root, sessionId: "sess-lost" });
  await first.port.fork("w1", "role: coder — long job");
  // No turn end: the process that could report it is gone.
  const second = world({ root, sessionId: "sess-lost" });
  const report = await within(second.port.join());
  assert.equal(report.ok, true);
  assert.equal(report.name, "w1");
  assert.equal(report.error?.name, "WORKER_LOST");
});

test("a steer keeps the old result and both turns are joined once each", async () => {
  // Results are per TURN and a returned result is never withdrawn: a finished
  // turn whose report nobody joined is work the worker did and the Manager has
  // not seen, so it stays queued ahead of the new turn (§4.1).
  const root = tempRoot();
  const w = world({ root, sessionId: "sess-steer" });
  await w.port.fork("w1", "role: coder — first");
  const session = w.lanes.live("w1").session;
  session.endTurn("first answer");
  await new Promise((resolve) => setImmediate(resolve));
  // The finished worker is steered: the verboseIntent joins the same session
  // and a new turn starts. The first answer is still owed.
  const steered = await w.port.fork("w1", "role: coder — more");
  assert.deepEqual(steered, { name: "w1", steered: true, started: false });
  const first = await within(w.port.join());
  assert.equal(first.ok, true);
  assert.equal(first.text, "first answer");
  // The second turn is still running, so the next join waits for it.
  const joining = w.port.join();
  assert.equal(await quiet(joining), false, "the steered turn is still running");
  session.endTurn("second answer");
  const second = await within(joining);
  assert.equal(second.text, "second answer");
  // Each turn was handed over exactly once.
  const none = await within(w.port.join());
  assert.equal(none.ok, false);
  assert.equal(none.reason, "NOTHING_TO_JOIN");
});

test("the journal survives a torn last line", () => {
  const root = tempRoot();
  const file = workerRosterFile(root, "sess-torn");
  const roster = new WorkerRoster({ file });
  roster.record({ type: WORKER_RECORD_TYPES.FORK, name: "w1", agentId: "w1", role: "coder" });
  roster.record({ type: WORKER_RECORD_TYPES.SETTLE, name: "w1", agentId: "w1" });
  fs.appendFileSync(file, '{"v":1,"type":"worker-set', "utf8");
  const restored = new WorkerRoster({ file }).restored;
  assert.deepEqual(restored.entries.map((entry) => [entry.name, entry.state]), [["w1", "finished"]]);
  assert.deepEqual(restored.unclaimed.map((result) => result.name), ["w1"]);
});

test("replayRoster folds fork, steer, settle, claim and release in order", () => {
  const records = [
    { type: "worker-fork", name: "w1", agentId: "w1", role: "coder" },
    { type: "worker-fork", name: "w2", agentId: "w2", role: "devops" },
    { type: "worker-settle", name: "w1", agentId: "w1" },
    { type: "worker-settle", name: "w2", agentId: "w2" },
    { type: "worker-claim", name: "w2", agentId: "w2" },
  ];
  const restored = replayRoster(records);
  assert.deepEqual(restored.unclaimed.map((result) => result.name), ["w1"]);
  // A steer makes the same worker busy again and keeps the result nobody
  // claimed: results are per turn and are never withdrawn.
  const steered = replayRoster([...records, { type: "worker-steer", name: "w1", agentId: "w1" }]);
  assert.deepEqual(steered.unclaimed.map((result) => result.name), ["w1"]);
  assert.equal(steered.entries.find((entry) => entry.name === "w1").state, "running");
  // Each settle is one queued result, in settle order, and a claim takes the
  // oldest of that name. A settle for a name the replay holds as RUNNING is
  // ignored (it describes a turn the entry no longer records), so the second
  // turn is modelled as a steer followed by its own settle.
  const twice = replayRoster([
    ...records,
    { type: "worker-steer", name: "w1", agentId: "w1" },
    { type: "worker-settle", name: "w1", agentId: "w1", text: "second" },
    { type: "worker-claim", name: "w1", agentId: "w1" },
  ]);
  assert.deepEqual(twice.unclaimed.map((result) => result.name), ["w1"]);
  // A release drops the name; a later fork binds it again.
  const released = replayRoster([
    ...records,
    { type: "worker-release", name: "w1", agentId: "w1" },
    { type: "worker-fork", name: "w1", agentId: "w1", role: "coder" },
  ]);
  assert.equal(released.entries.find((entry) => entry.name === "w1").state, "running");
});

// ---------------------------------------------------------------------------
// The state machine.
//
// A scenario is a list of steps. Each step is one thing that can happen to a
// session: a fork, a steer of a finished worker, the end of a running worker's
// turn, a `join`, or a reload. The model below is the ground truth the roster
// is checked against.
// ---------------------------------------------------------------------------

const workerName = fc.constantFrom("devops", "coder-core", "coder-hosts", "coder-tests");

const stepArb = fc.oneof(
  { weight: 5, arbitrary: fc.record({ kind: fc.constant("fork"), name: workerName }) },
  { weight: 2, arbitrary: fc.record({ kind: fc.constant("steer"), name: workerName }) },
  { weight: 4, arbitrary: fc.record({ kind: fc.constant("complete"), pick: fc.nat() }) },
  { weight: 4, arbitrary: fc.record({ kind: fc.constant("join") }) },
  { weight: 2, arbitrary: fc.record({ kind: fc.constant("reload") }) },
);

const scenarioArb = fc.array(stepArb, { minLength: 1, maxLength: 20 });

// Run one scenario against a real `LaneTable` over a journal on disk. The model
// tracks which workers are running and which results a caller already claimed.
const runScenario = async (steps) => {
  const root = tempRoot();
  const sessionId = `sess-${Math.random().toString(36).slice(2, 10)}`;
  let current = world({ root, sessionId });
  const running = new Set();
  const sessions = new Map();
  const claimed = new Set();
  const joins = [];
  // The `join` calls still waiting on a table a reload has since replaced. A
  // real reload (`surface.mjs` `#newRoster`) RETIRES the table it drops: the
  // worker sessions stay alive and their turns now report to the rebuilt
  // roster, so a wait armed on the old table must end rather than hang.
  let forked = 0;
  // Every name this scenario really forked, so the drain knows which workers it
  // owes a claim for.
  const forkedNames = new Set();
  // A worker is a NAME; its work is a sequence of TURNS. Every `start` or
  // `steer` receipt that begins work is one more turn, and each turn owes
  // exactly one result (§4.1: `join` returns the first worker to come back, and
  // a continued worker comes back again).
  //
  // `turns` counts the turns each name has begun, so the n-th turn of `devops`
  // is keyed `devops#n`. `claimed` holds turn keys, `lastText` the text each
  // turn ended with, and `finished` the turn keys in the order they ended —
  // which is the order the roster queues their results.
  const turns = new Map();
  // What each turn is EXPECTED to say when the model ends it. A turn the model
  // ends through `complete` or `join` says its own text; a turn the drain ends
  // says `report of <name> (drain)`. The expectation is set when the turn is
  // begun, so an end can always be compared word for word.
  const turnText = new Map();
  const turnKey = (name, text) => `${name}#${text}`;
  const currentTurn = (name) => turnKey(name, turns.get(name) ?? 0);
  const beginTurn = (name, text = null) => {
    const count = (turns.get(name) ?? 0) + 1;
    turns.set(name, count);
    const key = turnKey(name, count);
    // A turn's text names its turn, so two turns of one worker can never be
    // confused when a report is compared word for word.
    turnText.set(key, text ?? `report of ${name} turn ${count}`);
    return key;
  };
  // What each turn said, keyed by turn, so a model-driven turn end and the
  // roster's own record can be compared word for word.
  const lastText = new Map();
  // Turn keys in the order their turns ended, which is the order the roster
  // queues their results: "first to come back" is decided by that order.
  const finished = [];
  // Names whose result the model has no history for (a worker the journal knew
  // before this scenario started). They are claimed once and not modelled.
  const unknown = new Set();

  const reload = () => {
    // A reload inside one process, exactly as `surface.mjs` `#newRoster` does it:
    // the old table is DETACHED (its lanes stop listening, its pending `join` is
    // released), and the rebuilt table learns the worker from the journal and
    // takes the still-running session over through `runningSession`.
    current.lanes.detach();
    current = world({ root, sessionId, running: (name) => sessions.get(name) ?? null });
  };

  // The running workers this table can still drive: the ones whose session it
  // holds. A restored entry has no session here; it is settled explicitly.
  const liveSessions = () => [...running].filter((name) => sessions.has(name));

  const account = (report) => {
    const reported = finished.find((key) => lastText.get(key) === report.text) ?? null;
    if (reported === null) {
      // A worker the journal knew before this scenario: claimed once, not
      // modelled.
      unknown.add(report.name);
      joins.push(report);
      return;
    }
    assert.equal(reported.split("#")[0], report.name, "the report is the turn that ended");
    const owed = finished.filter((key) => !claimed.has(key) && !unknown.has(key.split("#")[0]));
    assert.equal(reported, owed[0], "join answers the oldest unclaimed result");
    assert.equal(claimed.has(reported), false, "a result was handed over twice");
    claimed.add(reported);
    joins.push(report);
  };
  for (const step of steps) {
    if (step.kind === "fork") {
      // The model forks a name ONCE. A second fork of a name it already knows is
      // a continuation, not a new worker: the roster starts (or steers) the next
      // TURN on the same lane, and that turn is owed like any other (§4.1).
      if (forkedNames.has(step.name)) {
        const receipt = await current.port.fork(step.name, "role: coder — go");
        if (receipt.started === true || receipt.steered === true) {
          running.add(step.name);
          beginTurn(step.name);
          if (current.lanes.live(step.name) !== null) {
            sessions.set(step.name, current.lanes.live(step.name).session);
          }
        }
        continue;
      }
      const wasRunning = running.has(step.name);
      const receipt = await current.port.fork(step.name, "role: coder — go");
      if (receipt.steered === true) {
        // A steer goes to a worker this table holds — the lane it knows, or one
        // the journal restored. The model knows the same name is running.
        assert.equal(wasRunning || current.lanes.names().includes(step.name), true, "a steer needs a worker of that name");
        if (!running.has(step.name)) running.add(step.name);
        beginTurn(step.name);
        if (!sessions.has(step.name) && current.lanes.live(step.name) !== null) {
          sessions.set(step.name, current.lanes.live(step.name).session);
        }
        continue;
      }
      if (receipt.started !== true) {
        // A name whose lane is still running in this table refuses a second
        // start; the model already knows it is running, so nothing changed.
        assert.equal(wasRunning, true, JSON.stringify(receipt));
        continue;
      }
      running.add(step.name);
      beginTurn(step.name);
      sessions.set(step.name, current.lanes.live(step.name).session);
      if (!forkedNames.has(step.name)) forked += 1;
      forkedNames.add(step.name);
      continue;
    }
    if (step.kind === "steer") {
      if (!running.has(step.name)) {
        const receipt = await current.port.fork(step.name, "role: coder — more");
        // Not running: the fork either starts a new turn on the finished lane,
        // or is refused because the name is unknown to this table — in which
        // case this scenario never forked it and the model learns nothing.
        if (receipt.started === true || receipt.steered === true) {
          if (!forkedNames.has(step.name)) forked += 1;
          // Any receipt that began work is a new TURN, and that turn is owed:
          // a continuation of a finished worker is work like any other (§4.1).
          running.add(step.name);
          beginTurn(step.name);
          if (current.lanes.live(step.name) !== null) {
            sessions.set(step.name, current.lanes.live(step.name).session);
          }
          forkedNames.add(step.name);
        }
        continue;
      }
      await current.port.fork(step.name, "role: coder — more");
      beginTurn(step.name);
      continue;
    }
    if (step.kind === "complete") {
      const live = liveSessions().sort();
      if (live.length === 0) continue;
      const name = live[step.pick % live.length];
      const key = currentTurn(name);
      // The text names the turn as well as the step, so two turns of one worker
      // can never be confused word for word.
      const text = `report of ${name} turn ${turns.get(name)} (step ${step.pick})`;
      turnText.set(key, text);
      sessions.get(name).endTurn(text);
      finished.push(key);
      lastText.set(key, text);
      running.delete(name);
      continue;
    }
    if (step.kind === "reload") {
      reload();
      continue;
    }
    // A join never outlives its step. The model's queue is the settle order, so
    // it says exactly what the answer must be:
    //   1. an unclaimed turn is queued -> the OLDEST one, at once;
    //   2. otherwise a turn is running -> the join waits, the model ends ONE
    //      running turn (newest), and the join answers that turn;
    //   3. otherwise -> NOTHING_TO_JOIN.
    const pending = () => finished.filter((key) => !claimed.has(key));
    const join = current.port.join();
    if (pending().length > 0) {
      const report = await within(join);
      assert.equal(report.ok, true, "join answered nothing with a result available");
      account(report);
      continue;
    }
    if (running.size > 0) {
      assert.equal(await quiet(join), false, "join answered while a worker was still running");
      const next = [...running].at(-1);
      const nextKey = currentTurn(next);
      const text = turnText.get(nextKey) ?? `report of ${next} (join)`;
      turnText.set(nextKey, text);
      if (sessions.has(next)) sessions.get(next).endTurn(text);
      else current.lanes.settle(next, { text });
      finished.push(nextKey);
      lastText.set(nextKey, text);
      running.delete(next);
      const report = await within(join);
      assert.equal(report.ok, true, "join answered nothing with a result available");
      assert.equal(report.name, next, "join answers the turn that ended");
      assert.equal(report.text, text, "the report is the turn that ended");
      claimed.add(nextKey);
      joins.push(report);
      continue;
    }
    const report = await within(join);
    assert.equal(report.ok, false, "join answered a result with nothing to collect");
    assert.equal(report.reason, "NOTHING_TO_JOIN", JSON.stringify(report));
    assert.equal(pending().length, 0, "join answered nothing while a turn was owed");
  }

  // Drain: every forked worker must be claimable exactly once. A fixed point:
  // end one running turn (or settle one restored running name), let the host
  // deliver it, then `join` — account the turn it answers and go again, until
  // `join` says there is nothing left.
  for (;;) {
    // A result already queued is claimed FIRST: ending another turn before
    // collecting it would put the newer turn ahead of it in the model's view.
    if (finished.some((key) => !claimed.has(key))) {
      const report = await within(current.port.join());
      assert.equal(report.ok, true, "join answered nothing with a result available");
      account(report);
      continue;
    }
    const next = [...running].at(-1);
    if (next === undefined) break;
    const key = currentTurn(next);
    const text = turnText.get(key) ?? `report of ${next} (drain)`;
    turnText.set(key, text);
    if (sessions.has(next)) sessions.get(next).endTurn(text);
    else current.lanes.settle(next, { text });
    finished.push(key);
    lastText.set(key, text);
    running.delete(next);
  }
  // Every turn this scenario began was claimed exactly once.
  const unclaimedTurns = finished.filter((key) => !claimed.has(key) && !unknown.has(key.split("#")[0]));
  assert.deepEqual(unclaimedTurns, [], "a turn that started was never claimed");
  return { forked, claimed, finished, joins };
};

test("a claim survives a reload and a steer does not invent a turn", async () => {
  // The exact sequence a shrinker found: a turn ends, a join claims it, the
  // worker is continued, a second join claims that turn, the roster is reloaded,
  // and the worker is steered. Nothing may come back from the journal, and the
  // steer must join the turn that is still running, not start a third one.
  const root = tempRoot();
  let w = world({ root, sessionId: "sess-reload-steer" });
  // The session the reload must hand over: captured before the old table is
  // detached, because a detached table no longer answers for its lane.
  let session = null;
  const live = () => session;
  const reopen = () => world({ root, sessionId: "sess-reload-steer", running: () => session });
  await w.port.fork("devops", "role: coder — go");
  session = w.lanes.live("devops").session;
  live().endTurn("turn one");
  assert.equal((await within(w.port.join())).text, "turn one");
  // A continuation starts turn two; nothing has ended it yet, so it is ended
  // after the reload and the join that waits for it returns it there.
  await w.port.fork("devops", "role: coder — go");
  w.lanes.detach();
  w = reopen();
  // The rebuilt table adopts devops as a LIVE lane (the model passes a session),
  // so it is a running lane, not a restored placeholder.
  assert.notEqual(w.lanes.running("devops"), null);
  assert.deepEqual(w.lanes.names(), ["devops"]);
  const receipt = await w.port.fork("devops", "role: coder — more");
  assert.deepEqual(receipt, { name: "devops", steered: true, started: false });
  // One running turn, nothing queued: the join must wait for it.
  const joining = w.port.join();
  assert.equal(await quiet(joining), false, "join answered with only a running turn");
  live().endTurn("turn two (ended after the reload)");
  const report = await within(joining);
  assert.equal(report.text, "turn two (ended after the reload)");
  const none = await within(w.port.join());
  assert.equal(none.ok, false);
  assert.equal(none.reason, "NOTHING_TO_JOIN");
});

test("property: join never answers 'nothing' while a worker is running or unclaimed", async () => {
  await fc.assert(
    fc.asyncProperty(scenarioArb, async (steps) => {
      await runScenario(steps);
    }),
    { numRuns: 40 },
  );
});

test("property: every forked worker is claimed exactly once across reloads", async () => {
  await fc.assert(
    fc.asyncProperty(scenarioArb, async (steps) => {
      // Accounting is per TURN: every turn that started is claimed exactly
      // once, and every worker that was forked produced at least one turn.
      const { forked, claimed, finished } = await runScenario(steps);
      assert.equal(claimed.size, finished.length, "a turn was started and never claimed");
      assert.equal(forked > 0 && claimed.size === 0, false, "a worker was forked and never claimed");
    }),
    { numRuns: 30 },
  );
});

// One record of the journal, as the adapter writes it.
const journalStepArb = fc.oneof(
  { weight: 4, arbitrary: fc.record({ kind: fc.constant("fork"), name: workerName }) },
  { weight: 2, arbitrary: fc.record({ kind: fc.constant("steer"), name: workerName }) },
  { weight: 4, arbitrary: fc.record({ kind: fc.constant("settle"), name: workerName, text: fc.string() }) },
  { weight: 3, arbitrary: fc.record({ kind: fc.constant("claim"), name: workerName }) },
  { weight: 1, arbitrary: fc.record({ kind: fc.constant("release"), name: workerName }) },
);

test("a claim that follows a steer still removes the queued result", () => {
  // fork, settle t1, steer, claim: the steer makes the name RUNNING while t1's
  // result is still queued, and the join then claims t1. Replay must remove it,
  // or a rebuilt roster hands t1 over a second time.
  const restored = replayRoster([
    { type: "worker-fork", name: "coder-hosts", agentId: "coder-hosts", role: "coder" },
    { type: "worker-settle", name: "coder-hosts", agentId: "coder-hosts", text: "report of coder-hosts turn 1 (step 0)" },
    { type: "worker-steer", name: "coder-hosts", agentId: "coder-hosts" },
    { type: "worker-claim", name: "coder-hosts", agentId: "coder-hosts" },
    { type: "worker-settle", name: "coder-hosts", agentId: "coder-hosts", text: "report of coder-hosts turn 2 (step 0)" },
    { type: "worker-claim", name: "coder-hosts", agentId: "coder-hosts" },
  ]);
  assert.deepEqual(restored.unclaimed, [], "a claimed turn came back");
  assert.deepEqual(restored.entries.map((entry) => [entry.name, entry.state]), [["coder-hosts", "finished"]]);
});

test("property: a rebuilt roster owes exactly the results nobody claimed", async () => {
  await fc.assert(
    fc.asyncProperty(fc.array(journalStepArb, { minLength: 1, maxLength: 24 }), async (steps) => {
      const root = tempRoot();
      const file = workerRosterFile(root, "sess-journal");
      // The model: what each name's story says after the records replayed.
      const state = new Map();
      // Queued results in settle order, one per turn. A steer or a fork never
      // removes an entry: results are per turn and are never withdrawn.
      const unclaimed = [];
      const roster = new WorkerRoster({ file });
      for (const step of steps) {
        if (step.kind === "fork") {
          // A fork of a name that is not live starts a NEW worker (§4.1). It is
          // not a steer, so it withdraws nothing: a turn that already returned
          // and that no `join` claimed is still owed, because `join` reports the
          // first worker to come back and this one has not been collected yet.
          state.set(step.name, { state: "running", text: null });
          roster.record({ type: WORKER_RECORD_TYPES.FORK, name: step.name, agentId: step.name, role: "coder" });
          continue;
        }
        const known = state.get(step.name);
        if (known === undefined) continue;
        if (step.kind === "steer") {
          // A steer starts a new turn and withdraws nothing: an earlier turn's
          // unclaimed result stays queued ahead of it.
          if (known.state !== "finished") continue;
          known.state = "running";
          known.text = null;
          roster.record({ type: WORKER_RECORD_TYPES.STEER, name: step.name, agentId: step.name });
          continue;
        }
        if (step.kind === "settle") {
          if (known.state !== "running") continue;
          known.state = "finished";
          known.text = step.text;
          unclaimed.push({ name: step.name, text: step.text });
          roster.record({ type: WORKER_RECORD_TYPES.SETTLE, name: step.name, agentId: step.name, text: step.text });
          continue;
        }
        if (step.kind === "claim") {
          // A claim takes the OLDEST queued result of that name, whatever the
          // name's state is now: a steer can make a name running while an
          // earlier turn's result is still queued, and the claim that follows
          // took that result. A claim with nothing queued took nothing.
          const index = unclaimed.findIndex((result) => result.name === step.name);
          if (index === -1) continue;
          unclaimed.splice(index, 1);
          roster.record({ type: WORKER_RECORD_TYPES.CLAIM, name: step.name, agentId: step.name });
          continue;
        }
        // release
        state.delete(step.name);
        for (let index = unclaimed.length - 1; index >= 0; index -= 1) {
          if (unclaimed[index].name === step.name) unclaimed.splice(index, 1);
        }
        roster.record({ type: WORKER_RECORD_TYPES.RELEASE, name: step.name, agentId: step.name });
      }
      // A reload: a new roster over the same journal.
      const restored = new WorkerRoster({ file }).restored;
      assert.deepEqual(
        restored.unclaimed.map((result) => [result.name, result.text]),
        unclaimed.map((result) => [result.name, result.text]),
      );
      for (const result of restored.unclaimed) {
        assert.equal(result.ok, true);
        assert.equal(result.restored, true);
      }
      assert.deepEqual(
        restored.entries.map((entry) => [entry.name, entry.state]).sort(),
        [...state.entries()].map(([name, known]) => [name, known.state]).sort(),
      );
    }),
    { numRuns: 50 },
  );
});
