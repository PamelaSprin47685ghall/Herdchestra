// Cancellation propagation (root §1.1 item 2).
//
// Esc at a user-facing agent must stop every session it started, or the
// descendants keep spending the operator's tokens after the operator asked them
// to stop. These tests drive the real event pump and the real activation:
//
//   - a nested tree (a Manager, its coder, that coder's child, and fission
//     lanes) is interrupted breadth-first;
//   - a second event for the same session does not interrupt anything twice;
//   - the three deliberate interrupts (`this.ask`, a relay retiring its
//     incumbent, a fission present replacement) do not propagate;
//   - a cancelled session refuses `fork` and steer, and `join` reports the
//     worker as cancelled instead of as a worker that merely stopped.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { setup } from "./plugin.mjs";
import { cancellationRootOf, propagateCancellation } from "./cancel.mjs";
import { REFUSALS } from "./refusals.mjs";
import { RELAY_GATE, RELAY_PHASES, relayFile, relayRecord, writeRelay } from "./relay-record.mjs";

const RESET = (budget = 50) => ({
  kind: "resetScope",
  newScopeGoal: "test",
  newScopeRetentionBudget: budget,
});

const quiet = (ms = 30) => new Promise((resolve) => setTimeout(resolve, ms));

// A fake host whose event stream the test drives. The host reports a model for
// every session (a child created without one falls to the free default, which
// `model-inherit.mjs` refuses), and `wait` answers at once.
const fakeHost = async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "herd-cancel-"));
  const tools = new Map();
  const calls = [];
  let sequence = 0;
  let pending = null;
  const queued = [];
  const emit = (event) => {
    if (pending !== null) {
      const resolve = pending;
      pending = null;
      resolve({ done: false, value: event });
      return;
    }
    queued.push(event);
  };
  const subscription = {
    [Symbol.asyncIterator]: () => ({
      next: () =>
        new Promise((resolve) => {
          if (queued.length > 0) resolve({ done: false, value: queued.shift() });
          else pending = resolve;
        }),
      return: async () => ({ done: true }),
    }),
  };
  const domain = {
    create: async (input) => {
      sequence += 1;
      const id = `ses_${sequence}`;
      calls.push(["create", { ...input, id }]);
      return { data: { id, ...input } };
    },
    prompt: async (input) => {
      calls.push(["prompt", input]);
      return { data: {} };
    },
    synthetic: async (input) => {
      calls.push(["synthetic", input]);
      return { data: {} };
    },
    wait: async (input) => {
      calls.push(["wait", input]);
    },
    get: async (input) => {
      calls.push(["get", input]);
      return { data: { id: input.sessionID, model: { providerID: "kw92", id: "lazy/task" } } };
    },
    context: async (input) => {
      calls.push(["context", input]);
      return { data: [] };
    },
    interrupt: async (input) => {
      calls.push(["interrupt", input]);
    },
  };
  const ctx = {
    location: { directory: dir },
    options: {},
    agent: {
      transform: async (callback) => callback({ list: () => [], update() {} }),
    },
    tool: {
      transform: async (callback) => callback({ add: (tool) => tools.set(tool.name, tool), update() {}, remove() {} }),
      hook: async () => {},
    },
    session: {
      hook: async () => {},
      ...domain,
    },
    event: { subscribe: () => subscription },
  };
  const cleanup = await setup(ctx);
  const call = async (name, input, { agent = "coder", sessionID = "ses_main" } = {}) => {
    sequence += 1;
    const id = `call_${sequence}`;
    const scoped = { ...input, scope: input?.scope ?? RESET() };
    const event = { tool: name, sessionID, agent, messageID: `msg_${sequence}`, id, input: scoped };
    const result = await tools.get(name).execute(scoped, {
      sessionID,
      agent,
      messageID: event.messageID,
      id,
      signal: undefined,
      progress: async () => {},
    });
    return result.output;
  };
  return { dir, calls, emit, call, cleanup, interruptCount: () => calls.filter(([kind]) => kind === "interrupt").length };
};

const createdSessions = (host) => host.calls.filter(([kind]) => kind === "create").map(([, input]) => input.id);

test("an operator interrupt stops every nested descendant, once", async () => {
  const host = await fakeHost();
  // A Manager, its coder, that coder's child, and two fission lanes of the
  // coder: four levels' worth of sessions behind one operator Esc.
  const managerFork = await host.call(
    "js-manager",
    { code: 'return await fork("w1", "role: coder. audit");' },
    { agent: "Manager" },
  );
  assert.equal(managerFork.value.started, true, JSON.stringify(managerFork));
  const worker = createdSessions(host)[0];
  const childFork = await host.call("js-worker", { code: 'return await fork("w2", "role: coder. child");' }, { sessionID: worker });
  assert.equal(childFork.value.started, true, JSON.stringify(childFork));
  const child = createdSessions(host)[1];
  const fission = await host.call("js-worker", { code: 'return await this.fission(["lane a", "lane b"]);' }, { sessionID: worker });
  assert.equal(fission.value.ok, true, JSON.stringify(fission.value));
  const lanes = createdSessions(host).filter((id) => id !== worker && id !== child);
  assert.equal(lanes.length, 2);

  host.emit({ type: "session.execution.interrupted", data: { sessionID: "ses_main", agent: "Manager" } });
  await quiet();

  const interrupted = host.calls.filter(([kind]) => kind === "interrupt").map(([, input]) => input.sessionID);
  for (const id of [worker, child, ...lanes]) {
    assert.equal(interrupted.includes(id), true, `${id} was interrupted`);
  }
  assert.equal(interrupted.includes("ses_main"), false, "the root was already stopped by the operator");

  // A second event for the same round interrupts nothing twice.
  const before = host.interruptCount();
  host.emit({ type: "session.execution.interrupted", data: { sessionID: "ses_main", agent: "Manager" } });
  await quiet();
  assert.equal(host.interruptCount(), before, "a repeated event is idempotent");

  // The cancelled work may not start or steer workers.
  const refused = await host.call(
    "js-manager",
    { code: 'return await fork("w3", "role: coder. more");' },
    { agent: "Manager" },
  );
  assert.equal(refused.value.ok, false, JSON.stringify(refused));
  assert.equal(refused.value.rejection, REFUSALS.CANCELLED);
  const steer = await host.call(
    "js-manager",
    { code: 'return await fork("w1", "role: coder. more");' },
    { agent: "Manager" },
  );
  assert.equal(steer.value.ok, false, JSON.stringify(steer));
  assert.equal(steer.value.rejection, REFUSALS.CANCELLED);

  // `join` reports a cancelled worker honestly, not as a worker that merely
  // stopped.
  const joined = await host.call("js-manager", { code: "return await join();" }, { agent: "Manager" });
  assert.equal(joined.value.ok, true, JSON.stringify(joined));
  assert.equal(joined.value.record.cancelled, true, JSON.stringify(joined.value.record));
  assert.equal(joined.value.record.outcome, "cancelled");
  await fs.rm(host.dir, { recursive: true, force: true });
});

test("a Plan's ask park is not an operator cancellation", async () => {
  const host = await fakeHost();
  // Plan's devops is a real descendant; it must survive the ask's own
  // interrupt, because the operator is answering, not stopping.
  const devops = await host.call("plan", { code: 'return await devops("look");' }, { agent: "Plan", sessionID: "ses_plan" });
  assert.equal(devops.value.ok, true, JSON.stringify(devops));
  const worker = createdSessions(host)[0];
  const asked = await host.call("plan", { code: 'return await this.ask("which file?");' }, { agent: "Plan", sessionID: "ses_plan" });
  assert.equal(asked.state, "parked", JSON.stringify(asked));
  const before = host.interruptCount();
  host.emit({ type: "session.execution.interrupted", data: { sessionID: "ses_plan", agent: "Plan" } });
  await quiet();
  assert.equal(host.interruptCount(), before, "an ask park propagates to nobody");
  assert.equal(
    host.calls.some(([kind, input]) => kind === "interrupt" && input.sessionID === worker),
    false,
    "the dedicated devops keeps running while the operator answers",
  );
  await fs.rm(host.dir, { recursive: true, force: true });
});

test("a relay's silencing of its incumbent is not an operator cancellation", async () => {
  const host = await fakeHost();
  const forked = await host.call("js-manager", { code: 'return await fork("w1", "role: coder. audit");' }, { agent: "Manager" });
  assert.equal(forked.value.started, true, JSON.stringify(forked));
  const worker = createdSessions(host)[0];
  // The hand-over writes the record and then interrupts the incumbent; the
  // record is the fact that says the successor now owns the work.
  const record = relayRecord({
    sessionID: "ses_main",
    incumbent: "ses_main",
    successor: "ses_successor",
    tenure: 1,
    milestone: "execute",
    phase: RELAY_PHASES.SENT,
    requestGate: RELAY_GATE.HOST,
    toolGate: RELAY_GATE.HOST,
  });
  await fs.mkdir(path.join(host.dir, ".Herdchestra"), { recursive: true });
  assert.equal(writeRelay(relayFile(host.dir, "ses_main"), record).ok, true);
  host.emit({ type: "session.execution.interrupted", data: { sessionID: "ses_main", agent: "Manager" } });
  await quiet();
  assert.equal(
    host.calls.some(([kind, input]) => kind === "interrupt" && input.sessionID === worker),
    false,
    "the successor's workers are preserved",
  );
  await fs.rm(host.dir, { recursive: true, force: true });
});

test("a fission present replacement and a non-user-facing session do not propagate", async () => {
  const activation = {
    agents: new Map([
      ["ses_worker", "coder"],
      ["ses_fission", "devops"],
    ]),
    parents: new Map([
      ["ses_worker", "ses_manager"],
      ["ses_fission", null],
    ]),
    lanes: new Map(),
    asks: { hasPending: () => false },
    presentReplacements: new Set(["ses_fission"]),
    states: new Map(),
    workers: new Map(),
    sessions: { interrupt: async () => {} },
    cancelled: new Set(),
  };
  assert.equal(cancellationRootOf({ activation, sessionID: "ses_worker", agent: "coder" }), null);
  assert.equal(cancellationRootOf({ activation, sessionID: "ses_fission", agent: "devops" }), null);
  // The same session as a Plan with a present replacement is still deliberate.
  activation.agents.set("ses_fission", "plan");
  assert.equal(cancellationRootOf({ activation, sessionID: "ses_fission", agent: "Plan" }), null);
  const result = await propagateCancellation({ activation, sessionID: "ses_fission", agent: "Plan" });
  assert.equal(result.propagated, false);
  assert.equal(activation.cancelled.size, 0);
});
