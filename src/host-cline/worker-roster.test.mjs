// The worker roster is durable, and `join` decides on turns, not on names
// (spec §4.1, §7.2).
//
// Two failures this file guards, both reachable in one host process:
//
//   early `null`  a worker is `fork`ed, a same-name `fork` queues a steer, the
//                 first turn settles — and a `join` that only asked "is any
//                 name still running?" answered `null` while the steered turn
//                 was already in flight. The verboseIntent was accepted and never
//                 joined.
//   lost roster   the plugin is loaded again while its delegated runtimes keep
//                 running in the host process. An in-memory roster answered
//                 "nothing to collect" for work that had not come back.
//
// A steered verboseIntent is a turn of its own, carried by the turn that is running
// (root §4.1): one report ends both, and each of them is a result of its own.
// The carrier's record carries the text that turn was given; each carried
// turn's record carries the report's text, because that report is its answer.
//
// The properties proved here, over arbitrary interleavings of `fork`, `steer`,
// out-of-order settlement, `join`, concurrent `join` and roster reloads:
//
//   progress    while any turn is in flight or any settled turn is unclaimed,
//               `join` never answers `null`;
//   accounting  every turn is claimed exactly once — a concurrent `join` never
//               sees the same record twice;
//   fidelity    a roster reloaded from what the table saved is the same roster.
//
// Run with: `node --test "src/host-cline/**/*.test.mjs"`.

import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import fc from "fast-check";

import { WorkerTable, WORKER_RECORD_TYPES, restoreRoster, restoredRoster, workerRosterFile } from "./workers.mjs";
import { readJsonLines } from "./files.mjs";
import { fcRuns } from "../core/test-runs.mjs";

const NAMES = ["devops", "coder-core", "coder-hosts"];

const workspace = async (t) => {
  const dir = await mkdtemp(join(tmpdir(), "herdchestra-roster-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
};

// A host whose delegated runtimes never settle on their own: every turn is
// finished by the test, in whatever order it chooses. Each handle reports its
// settlement through `onSettled`, exactly as `runtimeHandle` does.
const makeHost = () => {
  let created = 0;
  const runtimes = new Map();
  return {
    runtimes,
    create: async ({ name, verboseIntent = "" }) => {
      created += 1;
      const listeners = [];
      const runtime = {
        id: created,
        name,
        // The verboseIntent this runtime was started with, as `runtimeHandle` reports it.
        verboseIntent,
        turns: [],
        aborted: false,
        settle(record) {
          for (const listener of listeners) listener(record);
        },
        run: async (verboseIntent) => {
          runtime.turns.push(verboseIntent);
          return null;
        },
        abort: () => {
          runtime.aborted = true;
        },
        // The runtime's pending-input channel (`runtime.mjs`): a live worker's
        // verboseIntent enters the turn that is running instead of starting a new one.
        // The host consumes it at its next request boundary (`takeSteer`).
        steers: [],
        steer: (verboseIntent) => {
          // A steered verboseIntent rides the turn that is running: the queue takes it
          // at the next request boundary, and `finishTurn` reads it from there.
          runtime.steers.push(verboseIntent);
          return true;
        },
        takeSteer: () => runtime.steers.shift() ?? null,
        onSettled: (callback) => {
          listeners.push(callback);
        },
      };
      runtimes.set(name, [...(runtimes.get(name) ?? []), runtime]);
      // The handle the table holds: the runtime's own methods, reached through
      // the handle. A copy of the object would work too, but binding each one
      // keeps the fake honest about what the table can call.
      return {
        started: true,
        id: runtime.id,
        name: runtime.name,
        turns: runtime.turns,
        settle: runtime.settle,
        run: runtime.run,
        abort: runtime.abort,
        steer: runtime.steer,
        takeSteer: runtime.takeSteer,
        onSettled: runtime.onSettled,
      };
    },
    // The runtime a name is bound to right now.
    current(name) {
      return runtimes.get(name)?.at(-1) ?? null;
    },
  };
};

// Let every queued microtask run: a settle starts the next turn asynchronously,
// and the test reads the table only once that turn exists.
const flush = async () => {
  for (let tick = 0; tick < 4; tick += 1) await new Promise((resolve) => setImmediate(resolve));
};

// Whether a promise answered within a few macrotasks. A `join` that must not
// answer yet is checked this way; a regression fails the test instead of
// hanging it.
const quiet = async (promise) => {
  let answered = false;
  void promise.then(() => {
    answered = true;
  });
  await flush();
  return answered;
};

// A `join` that must NOT answer yet: it is left armed and the test continues.
// The promise is dropped, not awaited, because a correct table keeps it pending
// until a turn settles.
const unanswered = async (promise) => {
  assert.equal(await quiet(promise), false, "join answered while a turn was still owed");
};

// The promise, or a refusal after `ms`. The timer is CLEARED when the promise
// answers: a `join` that is legitimately still waiting in a later step must not
// be killed by this step's clock, and a stray timer keeps the event loop alive
// after the last test.
const within = (promise, ms = 1000) => {
  let timer = null;
  const clock = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`join did not answer within ${ms} ms`)), ms);
  });
  return Promise.race([promise, clock]).finally(() => clearTimeout(timer));
};

// ---------------------------------------------------------------------------
// The measured failures, as small tests.
// ---------------------------------------------------------------------------

test("a steer queued behind the first turn keeps join waiting for the steered turn", async () => {
  const host = makeHost();
  const table = new WorkerTable({ create: host.create });
  await table.fork({ name: "w", verboseIntent: "first", role: "coder" });
  const steered = await table.fork({ name: "w", verboseIntent: "second", role: "coder" });
  // The verboseIntent went INTO the running turn (root §4.1): the receipt is exactly
  // `{ name, steered, started }`, and the turn that is running is the one the
  // steer joined — not a second session.
  assert.deepEqual(steered, { name: "w", steered: true, started: false });
  // One turn is in flight. The steered verboseIntent is a turn of its own — it is
  // admitted, and it is owed a result — but the turn that took it carries it:
  // the runtime answers once, for both.
  assert.equal(table.owed(), 1, "the carrier and the verboseIntent it carries are one turn in flight");

  const pending = table.join();
  assert.equal(await quiet(pending), false, "join must not answer while a turn is in flight");

  // The turn reads the steer at its next request boundary and reports once,
  // with that verboseIntent's text. One report ends both turns, and each is a result
  // of its own: the carrier answers with the text it was given, the steered
  // turn with the text the runtime answered.
  host.current("w").settle({ finishReason: "stop", text: "second" });
  await flush();
  assert.equal((await within(pending)).text, "first", "the carrier is handed over first");
  assert.equal((await table.join()).text, "second", "the steered turn is handed over too");
  assert.equal(table.owed(), 0);
  assert.equal(await table.join(), null, "nothing is owed once every completion is claimed");
});

test("a steer after the worker settled is a new turn, and join waits for it", async () => {
  const host = makeHost();
  const table = new WorkerTable({ create: host.create });
  await table.fork({ name: "w", verboseIntent: "first", role: "coder" });
  host.current("w").settle({ finishReason: "stop", text: "first" });
  await flush();
  // The first turn is claimable; a second `fork` of the same name starts the
  // next turn instead of starting a second session.
  const again = await table.fork({ name: "w", verboseIntent: "second", role: "coder" });
  assert.deepEqual(again, { name: "w", steered: true, started: false });
  assert.equal(table.isLive("w"), true);
  assert.equal(table.owed(), 2, "one unclaimed completion plus the turn that just started");

  assert.equal((await table.join()).text, "first");
  const pending = table.join();
  assert.equal(await quiet(pending), false, "the second turn is still in flight");
  host.current("w").settle({ finishReason: "stop", text: "second" });
  assert.equal((await within(pending)).text, "second");
  assert.equal(await table.join(), null);
});

test("a settle nobody admitted is not booked as a completion", async () => {
  const host = makeHost();
  const table = new WorkerTable({ create: host.create });
  await table.fork({ name: "w", verboseIntent: "first", role: "coder" });
  const runtime = host.current("w");
  runtime.settle({ finishReason: "stop", text: "first" });
  // A runtime that reports the same turn twice (a host retry, a duplicated
  // event) must not create a second completion.
  runtime.settle({ finishReason: "stop", text: "first again" });
  await flush();
  assert.deepEqual(
    table.unclaimed().map((record) => record.text),
    ["first"],
  );
  assert.equal(table.owed(), 1);
  assert.equal((await table.join()).text, "first");
  assert.equal(await table.join(), null);
});

test("a roster reloaded from the journal keeps waiting on a worker that is still running", async (t) => {
  const root = await workspace(t);
  const host = makeHost();
  const session = "cline-roster";
  const first = new WorkerTable({
    create: host.create,
    root,
    sessionId: session,
    restored: restoredRoster(root, session),
  });
  await first.fork({ name: "devops", verboseIntent: "role: devops — probe", role: "devops" });
  await first.fork({ name: "coder-core", verboseIntent: "role: coder — edit", role: "coder" });
  // One of the two finishes and is claimed; the other keeps running.
  host.current("coder-core").settle({ finishReason: "stop", text: "edited" });
  await flush();
  assert.equal((await first.join()).name, "coder-core");

  // The plugin is loaded again into the same host process. Nothing was released
  // in between, so the journal is what the new table starts from.
  const second = new WorkerTable({
    create: host.create,
    root,
    sessionId: session,
    restored: restoredRoster(root, session),
  });
  assert.equal(second.size, 2, "the roster survived the reload");
  assert.equal(second.owed(), 1, "only the running worker's turn is owed");

  const pending = second.join();
  assert.equal(await quiet(pending), false, "join must not answer while devops is still running");
  // The runtime reports through the host, which names the worker, not the turn.
  second.settled("devops", { finishReason: "stop", text: "probed" });
  const record = await within(pending);
  assert.equal(record.name, "devops");
  assert.equal(record.text, "probed");
  assert.equal(await second.join(), null, "the claimed turn is not offered again");
});

test("a steer of a name whose runtime this load does not hold is refused, not queued", async (t) => {
  const root = await workspace(t);
  const host = makeHost();
  const session = "cline-restored-steer";
  const first = new WorkerTable({ create: host.create, root, sessionId: session });
  await first.fork({ name: "w", verboseIntent: "first", role: "coder" });
  const second = new WorkerTable({
    create: host.create,
    root,
    sessionId: session,
    restored: restoredRoster(root, session),
  });
  const refused = await second.fork({ name: "w", verboseIntent: "second", role: "coder" });
  assert.equal(refused.started, false);
  assert.equal(refused.steered, false);
  assert.equal(refused.rejection, "WORKER_RUNTIME_GONE", "a verboseIntent nobody will drain is not accepted");
  assert.equal(second.owed(), 1, "the refused verboseIntent admitted no turn");
});

test("a released session settles what it owed, so the next load does not wait forever", async (t) => {
  const root = await workspace(t);
  const host = makeHost();
  const session = "cline-release";
  const first = new WorkerTable({ create: host.create, root, sessionId: session });
  await first.fork({ name: "w", verboseIntent: "first", role: "coder" });
  // The turn is still in flight when the session ends: the abort is what makes
  // it never report, and the release is what keeps the next load from waiting.
  const receipt = first.abortAll();
  assert.deepEqual(receipt.released, ["w"]);
  assert.equal(first.owed(), 0, "an aborted turn never reports, so it is not left owed");

  const second = new WorkerTable({
    create: host.create,
    root,
    sessionId: session,
    restored: restoredRoster(root, session),
  });
  assert.equal(await second.join(), null, "a released session has nothing to collect");
});

test("restoreRoster replays fork, steer, settle, claim and release in order", () => {
  const records = [
    { type: WORKER_RECORD_TYPES.FORK, name: "w1", role: "coder", state: "running", admitted: 1, settled: 0 },
    { type: WORKER_RECORD_TYPES.FORK, name: "w2", role: "devops", state: "running", admitted: 1, settled: 0 },
    { type: WORKER_RECORD_TYPES.STEER, name: "w1", admitted: 2 },
    { type: WORKER_RECORD_TYPES.SETTLE, name: "w1", turn: 1, outcome: { finishReason: "stop", text: "one" } },
    { type: WORKER_RECORD_TYPES.SETTLE, name: "w2", turn: 1, outcome: { finishReason: "stop", text: "two" } },
    { type: WORKER_RECORD_TYPES.CLAIM, name: "w2", id: "w2#1", turn: 1 },
  ];
  const restored = restoreRoster(records);
  assert.deepEqual(
    restored.completions.map((entry) => entry.id),
    ["w1#1"],
    "the claimed turn is not offered again",
  );
  assert.deepEqual([...restored.claims], ["w2#1"]);
  const w1 = restored.entries.find((entry) => entry.name === "w1");
  assert.deepEqual([w1.admitted, w1.settled, w1.state], [2, 1, "running"], "the steered turn is still in flight");

  // A release settles what the name still owed.
  const released = restoreRoster([...records, { type: WORKER_RECORD_TYPES.RELEASE, name: "w1" }]);
  const stopped = released.entries.find((entry) => entry.name === "w1");
  assert.equal(stopped.state, "dead");
  assert.equal(stopped.settled, stopped.admitted);
});

test("the journal records one line per change, and the journal is what the next load reads", async (t) => {
  const root = await workspace(t);
  const host = makeHost();
  const session = "cline-journal";
  const table = new WorkerTable({ create: host.create, root, sessionId: session });
  await table.fork({ name: "w", verboseIntent: "first", role: "coder" });
  await table.fork({ name: "w", verboseIntent: "second", role: "coder" });
  // One report: the runtime took the steered verboseIntent at its request boundary and
  // answered with it, so this text settles both turns — the carrier and the
  // steer it carried — and each is a completion nobody has claimed.
  host.current("w").settle({ finishReason: "stop", text: "second" });
  await flush();
  assert.equal((await table.join()).text, "first", "the carrier answers with the text it was given");
  assert.equal((await table.join()).text, "second", "the steered turn answers with the report's text");
  const { file } = workerRosterFile(root, session);
  const types = readJsonLines(file).map((record) => record.type);
  assert.deepEqual(
    types,
    ["fork", "steer", "settle", "claim", "claim"],
    "one record per change: one settlement carries both turns, and each is claimed once",
  );
  const settle = readJsonLines(file).find((record) => record.type === "settle");
  assert.deepEqual([settle.turn, settle.steered], [2, 1], "the report names the last turn it settled and how many it carried");

  const saved = restoredRoster(root, session);
  assert.deepEqual(saved.entries.map((entry) => entry.name), ["w"]);
  // The journal replays to the same two turns, each handed over once.
  const replayed = restoreRoster(readJsonLines(file));
  assert.deepEqual([...replayed.claims].sort(), ["w#1", "w#2"]);
  assert.deepEqual(
    replayed.entries.map((entry) => [entry.name, entry.admitted, entry.settled]),
    [["w", 2, 2]],
  );
  assert.deepEqual(saved.claims, ["w#1", "w#2"]);
});

// ---------------------------------------------------------------------------
// The state machine.
//
// A scenario is a list of steps. Each step is one thing that can happen to a
// Manager session: a `fork`, a same-name `steer`, the host finishing a runtime
// in any order, a `join`, two concurrent `join`s, or a reload of the plugin
// into the same process. The model below is the ground truth the table is
// checked against.
// ---------------------------------------------------------------------------

const nameArb = fc.constantFrom(...NAMES);

const stepArb = fc.oneof(
  { weight: 6, arbitrary: fc.record({ kind: fc.constant("fork"), name: nameArb }) },
  { weight: 3, arbitrary: fc.record({ kind: fc.constant("steer"), name: nameArb }) },
  { weight: 5, arbitrary: fc.record({ kind: fc.constant("complete"), pick: fc.nat() }) },
  { weight: 4, arbitrary: fc.record({ kind: fc.constant("join") }) },
  { weight: 2, arbitrary: fc.record({ kind: fc.constant("joinTwice") }) },
  { weight: 2, arbitrary: fc.record({ kind: fc.constant("reload") }) },
);

const scenarioArb = fc.array(stepArb, { minLength: 1, maxLength: 22 });

// One scenario against a real `WorkerTable` over a journal on disk.
//
// The model tracks, per name, the runtime this process holds and whether that
// runtime's current turn is still in flight. A reload builds a second table
// from the journal, exactly as a second plugin load does.
const runScenario = async (steps) => {
  const host = makeHost();
  const root = await mkdtemp(join(tmpdir(), "herdchestra-roster-p-"));
  const session = "cline-property";
  let table = new WorkerTable({ create: host.create, root, sessionId: session, restored: restoredRoster(root, session) });
  // The scenario so far. A failure in a fast-check run is reported with a
  // shrunk step list, but not with the state the model was in when the table
  // disagreed with it; this is what makes the report replayable by hand.
  const trace = [];
  // name -> { known, runtime, running, carrier, carrierAt, carried }. `carrier`
  // is the verboseIntent of the turn that is running, and `carrierAt` is the turn's slot
  // in `started`. `carried` holds the steered verboseIntents that turn took, each with
  // its own slot: a steered verboseIntent is a turn of its own, carried by the turn that
  // read it.
  const model = new Map();
  const claimed = [];
  // Claims are counted, not just remembered: one report can settle two turns
  // with the same text, and each is handed over once.
  const claimedIds = new Map();
  const claimedCount = (id) => claimedIds.get(id) ?? 0;

  // Every turn this scenario ever began, as `<name>:<text>`. A turn is a record
  // nobody may lose, so this is the set the accounting property is stated over.
  // A turn is named by its verboseIntent until the report that ends it arrives, and by
  // that report's text afterwards; the slots make the rename exact, whatever
  // two turns happen to be called.
  const started = [];
  // Every record the table queued, in the order it queued it, as
  // `<name>:<text>`: one report settles the turn that was running. `join` hands
  // these over in this order, once each.
  const queued = [];
  // The records the model queued that nobody claimed yet, in queue order: what
  // the next `join` must hand over, oldest first.
  const owedRecords = () => queued.filter((id) => claimedCount(id) < queued.filter((other) => other === id).length);
  let turns = 0;
  const nextBrief = (name, kind) => {
    turns += 1;
    return `${kind} ${turns} ${name}`;
  };

  const reload = () => {
    table = new WorkerTable({ create: host.create, root, sessionId: session, restored: restoredRoster(root, session) });
  };

  const running = () => [...model.entries()].filter(([, entry]) => entry.running);

  // The runtime of `entry` finishes its current turn. One report answers the
  // turn that was running AND every steered verboseIntent that turn took: the runtime
  // reads its pending-input queue at a request boundary, so it answers once,
  // with the text of the last verboseIntent it read.
  const finishTurn = (entry, outcome) => {
    const text = entry.carried.length > 0 ? entry.carried.at(-1).verboseIntent : entry.carrier;
    if (entry.runtime !== null) entry.runtime.settle({ finishReason: outcome, text });
    else table.settled(entry.name, { finishReason: outcome, text });
    // One result per turn (root §4.1). The carrier's record carries the text of
    // the turn it was given — a turn this load started knows it; a turn restored
    // from the journal does not, and the report's text is the only text there
    // is. Every carried verboseIntent is a turn of its own, and each of them ended with
    // this one report, so each carries this report's text.
    const reported = `${entry.name}:${text}`;
    started[entry.carrierAt] = entry.known === true ? `${entry.name}:${entry.carrier}` : reported;
    for (const carried of entry.carried) started[carried.at] = reported;
    queued.push(started[entry.carrierAt], ...entry.carried.map(() => reported));
    // The report ended every turn it carried, so none of them is in flight any
    // more. Cleared AFTER the rename: a turn forgotten here is a turn the
    // accounting below would report as started and never claimed.
    entry.carried = [];
    entry.carrier = null;
    entry.carrierAt = -1;
    entry.running = false;
  };

  const claimOf = (record) => {
    assert.notEqual(
      record,
      null,
      `join answered nothing while a turn was still owed (queued ${JSON.stringify(queued)}, model ${JSON.stringify([...model.values()])})`,
    );
    // The record answers the oldest turn nobody claimed, in queue order. The
    // queue is a multiset: a record with this name and text must still be owed.
    const owed = queued.filter((id) => claimedCount(id) < queued.filter((other) => other === id).length);
    assert.notEqual(owed[0], undefined, `join answered a record nobody owed: ${record.name} / ${record.text}`);
    const expected = `${record.name}:${record.text}`;
    assert.equal(owed[0], expected, `join answered the oldest unclaimed turn (owed ${JSON.stringify(owed)}, got ${expected})`);
    claimedIds.set(expected, claimedCount(expected) + 1);
    claimed.push(record);
  };

  try {
    for (const step of steps) {
      trace.push(JSON.stringify(step));
      if (step.kind === "fork" || step.kind === "steer") {
        const role = step.name === "devops" ? "devops" : "coder";
        const verboseIntent = nextBrief(step.name, step.kind);
        const receipt = await table.fork({ name: step.name, verboseIntent, role });
        const entry = model.get(step.name) ?? null;
        // A name this load does not hold a runtime for — one it never forked, or
        // one restored from the journal — cannot be steered. The table refuses
        // the verboseIntent, so it admits no turn. Whatever the roster already owed for
        // that name came with the journal: it is a turn this scenario never
        // began, and the drain must account for it like any other.
        if (receipt.rejection !== undefined) {
          assert.equal(receipt.rejection, "WORKER_RUNTIME_GONE", "a verboseIntent nobody will drain is not accepted");
          const restored = table.get(step.name);
          // A turn that came with the roster is a turn this scenario already
          // owes: the model keeps the slot it has, and the reload already named
          // it after the worker. A name the model holds no turn for is owed the
          // one the journal kept running, which this load did not start.
          if (restored?.state === "running" && !(entry !== null && entry.running === true)) {
            model.set(step.name, {
              name: step.name,
              known: false,
              runtime: null,
              running: true,
              carrier: step.name,
              carrierAt: started.push(`${step.name}:${step.name}`) - 1,
              carried: [],
            });
          } else if (restored?.state !== "running" && entry !== null) {
            entry.running = false;
            entry.carrier = null;
            entry.carrierAt = -1;
            entry.carried = [];
          }
          continue;
        }
        // A verboseIntent for a worker whose turn is in flight rides that turn: it is
        // admitted, owed and handed over, but it starts nothing.
        if (entry !== null && entry.known === true && entry.running === true) {
          assert.deepEqual(receipt, { name: step.name, steered: true, started: false }, "a steer reaches the running turn");
          // The verboseIntent rides the turn that is running: that turn's one report
          // answers it too, and its record carries that report's text.
          entry.carried.push({ verboseIntent, at: started.push(`${step.name}:${verboseIntent}`) - 1 });
          continue;
        }
        // A verboseIntent for an idle worker this load holds starts the next turn on the
        // same runtime. The verboseIntent IS that turn, so it is recorded once.
        if (entry !== null && entry.known === true) {
          assert.deepEqual(receipt, { name: step.name, steered: true, started: false }, "a known name never starts a second runtime");
          entry.running = true;
          entry.runtime = host.current(step.name);
          entry.carrier = verboseIntent;
          entry.carrierAt = started.push(`${step.name}:${verboseIntent}`) - 1;
          entry.carried = [];
          continue;
        }
        // A name this load has no runtime for, and the table started one: this
        // is the turn the fork admitted.
        assert.equal(receipt.started, true, "a verboseIntent for a new name starts a runtime");
        const fresh = {
          name: step.name,
          known: true,
          runtime: host.current(step.name),
          running: true,
          carrier: verboseIntent,
          carrierAt: started.push(`${step.name}:${verboseIntent}`) - 1,
          carried: [],
        };
        model.set(step.name, fresh);
        continue;
      }

      if (step.kind === "complete") {

        const live = running();
        if (live.length === 0) continue;
        const [name, entry] = live[step.pick % live.length];
        // The turn answers with the verboseIntent that started it, so a claimed record
        // names the exact turn it came from.
        finishTurn(entry, step.pick % 3 === 0 ? "error" : "stop");
        await flush();
        continue;
      }

      if (step.kind === "reload") {
        // A reload saves the table as it is. A worker still in flight is saved
        // running; the host runtimes are the same processes, so what the model
        // knows about their turns stays.
        // The turns in flight, counted BEFORE the reload drops the runtimes: one
        // per running worker, however many steered verboseIntents it took. A carried
        // verboseIntent is a turn of its own, but not a second turn in flight — the one
        // report that ends the carrier ends it too, and the restored roster owes
        // the same one report.
        const owedTurns = running().length;
        reload();
        // The runtime handles this process holds are gone; what the model knows
        // about them stays, because the host runtimes are the same processes.
        // But the new table holds NO handle: a restored worker's report arrives
        // as a host event naming the worker (`table.settled`), which is the only
        // channel a runtime this load did not start has.
        for (const entry of model.values()) {
          entry.known = false;
          entry.runtime = null;
          // The journal carries counters, not verboseIntents: this load cannot name the
          // turn it kept running until that turn reports. Its slot in `started`
          // keeps it, and `finishTurn` renames it from the report.
          if (entry.running === true) entry.carrier = entry.name;
        }
        // The restored table is the one the model must agree with: every turn
        // the model still owes, plus every completion nobody claimed.
        assert.equal(
          table.owed(),
          owedTurns + table.unclaimed().length,
          `the restored ledger is the live one (table ${table.owed()}, model ${owedTurns}, unclaimed ${table.unclaimed().length})`,
        );
        continue;
      }

      // A join, or two of them at once. Nothing finishes by itself, so an armed
      // join can only answer a settle. The model therefore never leaves a turn
      // running for a join to watch: every turn in flight ends here, so every
      // join this step asks has a record to hand over — the oldest one nobody
      // claimed. A join that answered `null` with such a turn still running
      // would be the failure this property names, and it is caught below by the
      // record the model expected and did not get.
      const joins = step.kind === "joinTwice" ? [table.join(), table.join()] : [table.join()];
      if (owedRecords().length === 0 && running().length > 0) {
        // Nothing is claimable yet and a turn is in flight, so not one of the
        // joins armed here may answer. They are the joins that then take the
        // records the finished turn queues: a probe of its own would be a waiter
        // nobody accounts for, and it would take a record this step owes.
        for (const join of joins) assert.equal(await quiet(join), false, "join answered nothing while a turn was in flight");
        assert.equal(table.owed() > 0, true, "a join that waits waits on a turn the table owes");
        finishTurn(running()[0][1], "stop");
        await flush();
      }
      // Two concurrent joins are two claims: the table hands each one a
      // different turn, and the model accounts for each as it arrives. A join
      // that is still waiting is waiting on a turn that has not reported, so the
      // model ends one — exactly what the host does — until it answers. `null`
      // is therefore only ever the table's honest "nothing is owed", never a
      // join that gave up on a turn still in flight.
      const answer = (record) => {
        if (record !== null) {
          claimOf(record);
          return;
        }
        // The table owes nothing. A turn the model still holds in flight is
        // exactly the "answered nothing while a turn was owed" failure; a record
        // the model expected and the table never queued is the missing-
        // completion failure the accounting assertion reports.
        assert.equal(running().length, 0, "join answered nothing while a turn was still owed");
        assert.equal(table.unclaimed().length, 0, "join answered nothing with an unclaimed result");
      };
      for (const join of joins) {
        for (;;) {
          const settled = await Promise.race([join.then((record) => ({ record })), flush().then(() => null)]);
          if (settled !== null) {
            answer(settled.record);
            break;
          }
          if (owedRecords().length > 0) {
            // A record is claimable now: this join takes it on its next check.
            answer(await within(join));
            break;
          }
          assert.equal(running().length > 0, true, "a join that waits waits on a turn the table owes");
          finishTurn(running()[0][1], "stop");
          await flush();
        }
      }
    }

    // Drain: every turn the model still owes must be claimable exactly once.
    // The model is the authority on what is in flight, so each pass either
    // claims a record the model already queued or ends the turn the model says
    // is running.
    for (;;) {
      const pendingRecords = owedRecords();
      if (pendingRecords.length > 0) {
        claimOf(await within(table.join()));
        continue;
      }
      if (running().length === 0) break;
      finishTurn(running()[0][1], "stop");
      await flush();
    }
    assert.equal(await within(table.join()), null, "the drained roster has nothing left");
    // The model and the table must agree on what is left; a disagreement here is
    // the missing record, reported before the accounting assertion below.
    assert.equal(table.owed(), 0, `the table still owes ${table.owed()}: queued ${JSON.stringify(queued)}, model ${JSON.stringify([...model.values()])}`);
    // Every turn this scenario began was claimed exactly once, and nothing else
    // was handed over: each record answered one turn the model began. A steered
    // verboseIntent is a turn like any other — it rides the turn that took it, and the
    // record it is handed over as carries that turn's text.
    // One record per turn: a settle of a steered turn queues the carrier and the
    // steered turn, and each is handed over exactly once. Two turns may carry
    // the same text, so the comparison is on the multiset of `name:text`.
    const handedOver = [...claimedIds].flatMap(([id, count]) => Array.from({ length: count }, () => id));
    assert.deepEqual(
      handedOver.sort(),
      [...started].sort(),
      `a turn was started and never claimed; queued ${JSON.stringify(queued)}; handed over ${JSON.stringify(handedOver)}`,
    );
    return { claimed, started, turns: queued.length, table, root, session };
  } catch (error) {
    // A shrunk fast-check case names the steps, not the state the table was in
    // when it disagreed with the model. The trace is the missing half.
    error.message = `${error.message}\n  steps: ${trace.join("\n         ")}`;
    throw error;
  } finally {
    await rm(root, { recursive: true, force: true });
  }
};

test("property: join never answers nothing while a turn is in flight or unclaimed", async () => {
  await fc.assert(
    fc.asyncProperty(scenarioArb, async (steps) => {
      await runScenario(steps);
    }),
    { ...fcRuns(80) },
  );
});

test("property: every finished turn is claimed exactly once, concurrent joins included", async () => {
  await fc.assert(
    fc.asyncProperty(scenarioArb, async (steps) => {
      // `runScenario` rejects a duplicate, refuses a record nobody owed, and
      // states that every begun turn was claimed. Here the fact is stated over
      // the returned counts: the turns handed over are exactly the turns the
      // scenario began, and each was handed over once.
      const { claimed, started, turns } = await runScenario(steps);
      assert.equal(claimed.length, started.length, "a record was handed over twice");
      assert.equal(turns, started.length, "a turn that ended was never claimed");
    }),
    { ...fcRuns(60) },
  );
});

test("property: a roster reloaded from the table's own journal is the same roster", async () => {
  await fc.assert(
    fc.asyncProperty(scenarioArb, async (steps) => {
      const host = makeHost();
      const root = await mkdtemp(join(tmpdir(), "herdchestra-roster-r-"));
      const session = "cline-fidelity";
      try {
        const first = new WorkerTable({ create: host.create, root, sessionId: session, restored: restoredRoster(root, session) });
        const names = steps.filter((step) => step.kind === "fork" || step.kind === "steer").map((step) => step.name);
        for (const name of names) {
          await first.fork({ name, verboseIntent: "go", role: name === "devops" ? "devops" : "coder" });
        }
        // Finish every turn so the journal carries completions too. A worker
        // left mid-turn would be saved as running, not as finished. One report
        // settles ONE turn, and a steered verboseIntent is a turn of its own, so a
        // worker that took two steers owes three reports — the loop settles
        // every turn each name still owes, whatever its state says.
        const admitted = names.reduce((count, name) => count + (first.get(name)?.admitted ?? 0), 0);
        for (let round = 0; round < admitted + 1; round += 1) {
          const owing = [...new Set(names)].filter((name) => {
            const entry = first.get(name);
            return entry !== null && entry.admitted > entry.settled;
          });
          if (owing.length === 0) break;
          for (const name of owing) host.current(name)?.settle({ finishReason: "stop", text: `done ${name} ${round + 1}` });
          await flush();
        }
        assert.equal(first.owed(), first.unclaimed().length, "every turn of the fixture settled");
        const roster = first.snapshot();
        const reloaded = new WorkerTable({
          create: host.create,
          root,
          sessionId: session,
          restored: restoredRoster(root, session),
        });
        assert.deepEqual(reloaded.snapshot(), roster, "the reloaded roster differs from the live one");
        assert.equal(reloaded.size, first.size);
        assert.equal(reloaded.owed(), first.owed());
        // The journal the table wrote replays to the same entries as well.
        const replayed = restoreRoster(readJsonLines(workerRosterFile(root, session).file));
        assert.deepEqual(
          replayed.entries.map((entry) => [entry.name, entry.admitted, entry.settled]).sort(),
          roster.entries.map((entry) => [entry.name, entry.admitted, entry.settled]).sort(),
        );
      } finally {
        await rm(root, { recursive: true, force: true });
      }
    }),
    { ...fcRuns(50) },
  );
});

// ---------------------------------------------------------------------------
// One DevOps at a time (根 §2.1, §2.3)
// ---------------------------------------------------------------------------

test("a second active devops is refused by name; the same name is a steer", async () => {
  const host = makeHost();
  const table = new WorkerTable({ create: host.create });
  assert.equal((await table.fork({ name: "d1", verboseIntent: "role: devops — run it", role: "devops" })).started, true);
  const refused = await table.fork({ name: "d2", verboseIntent: "role: devops — run it too", role: "devops" });
  assert.equal(refused.started, false);
  assert.equal(refused.steered, false);
  assert.equal(refused.rejection, "DEVOPS_ALREADY_ACTIVE");
  assert.equal(refused.detail.name, "d1", "the refusal names the worker to steer");
  // The same name is not a second worker: it steers the one that exists.
  const steered = await table.fork({ name: "d1", verboseIntent: "role: devops — more", role: "devops" });
  assert.deepEqual(steered, { name: "d1", steered: true, started: false });
  // A coder is unaffected.
  assert.equal((await table.fork({ name: "c1", verboseIntent: "role: coder — edit", role: "coder" })).started, true);
  // A devops whose turns all settled owes nothing, so a new one may start.
  host.current("d1").settle({ finishReason: "stop", text: "done" });
  await flush();
  assert.equal(table.isLive("d1"), false);
  assert.equal((await table.fork({ name: "d3", verboseIntent: "role: devops — next", role: "devops" })).started, true);
});

// ---------------------------------------------------------------------------
// A replaced present (spec §4.2 step 6)
// ---------------------------------------------------------------------------

test("a replacement the host cannot perform is a refusal compose can read", async () => {
  const table = new WorkerTable({
    create: async () => ({
      started: true,
      run: () => new Promise(() => {}),
      addTools() {},
      notifyPendingUserMessage() {},
    }),
  });
  assert.deepEqual(table.replacePresent("nope"), { ok: false, replaced: false, reason: "OWNER_UNKNOWN", owner: "nope" });
  await table.fork({ name: "w", verboseIntent: "b", role: "coder" });
  // This handle has neither an abort nor a turn-end boundary: compose's
  // `portRefused` reads `ok: false`, so the group rolls back instead of going
  // live beside a present that keeps producing turns.
  const refused = table.replacePresent("w");
  assert.equal(refused.ok, false);
  assert.equal(refused.replaced, false);
  assert.equal(refused.reason, "NO_ABORT_SURFACE");
});

test("a same-name fork never restarts a replaced present", async () => {
  const turns = [];
  const table = new WorkerTable({
    create: async () => ({
      started: true,
      run: async (verboseIntent) => {
        turns.push(verboseIntent);
        return null;
      },
      abort() {},
      addTools() {},
      notifyPendingUserMessage() {},
      atTurnEnd: () => true,
    }),
  });
  await table.fork({ name: "w", verboseIntent: "first", role: "coder" });
  assert.equal(table.replacePresent("w").replaced, true);
  const refused = await table.fork({ name: "w", verboseIntent: "again", role: "coder" });
  assert.equal(refused.started, false);
  assert.equal(refused.rejection, "WORKER_REPLACED");
  assert.deepEqual(turns, [], "the silenced present runs no further turn");
});

// ---------------------------------------------------------------------------
// A settlement counts only the steered verboseIntents the turn actually read (§4.1)
// ---------------------------------------------------------------------------

test("the table settles only the steered verboseIntents the turn actually read", async () => {
  const host = makeHost();
  const table = new WorkerTable({ create: host.create });
  await table.fork({ name: "w", verboseIntent: "first", role: "coder" });
  assert.deepEqual(await table.fork({ name: "w", verboseIntent: "second", role: "coder" }), {
    name: "w",
    steered: true,
    started: false,
  });
  // The turn ends without another request boundary, so the runtime reports that
  // it read NO steer. Settling the queued verboseIntent anyway handed `join` work
  // nobody did.
  host.current("w").settle({ finishReason: "stop", text: "first only", steered: 0 });
  await flush();
  const carrier = await table.join();
  assert.equal(carrier.text, "first only", "the carrier's own turn is handed over");
  assert.equal(await table.join(), null, "the unread verboseIntent is not reported as finished work");
});

test("the FORK record reaches the journal before a buffered settlement", async () => {
  // The runtime can settle its first turn while `create` is still awaited. The
  // listener is registered AFTER the FORK record, so a replay never sees a
  // SETTLE for a name it has not met (it would drop it, and `join` would wait
  // forever on the next load).
  const records = [];
  const table = new WorkerTable({
    create: async () => ({
      started: true,
      onSettled: (callback) => callback({ finishReason: "stop", text: "early" }),
      run: async () => null,
      addTools() {},
      notifyPendingUserMessage() {},
    }),
    onRecord: (record) => records.push(record.type),
  });
  await table.fork({ name: "w", verboseIntent: "b", role: "coder" });
  assert.deepEqual(records.slice(0, 2), ["fork", "settle"]);
});

test("a restored roster never offers a lane's settlement to join", () => {
  const table = new WorkerTable({
    create: async () => ({ started: true, run: async () => null }),
    restored: {
      entries: [],
      completions: [
        { id: "g#0", name: "g#0", role: "coder", turn: 1, outcome: { finishReason: "stop", text: "lane" }, lane: true },
      ],
      claims: [],
    },
  });
  assert.deepEqual(table.unclaimed(), [], "a lane's turn is its group's delivery, never a join completion");
  assert.equal(table.owed(), 0);
});
