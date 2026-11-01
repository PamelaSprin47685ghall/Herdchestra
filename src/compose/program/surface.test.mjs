import test from "node:test";
import assert from "node:assert/strict";

import { ROLES, SURFACE_MEMBERS, surfaceNameFor, ProgramSurface } from "./surface.mjs";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { ProgramRegistry, ProgramState } from "../../core/program.mjs";
import { fakeClock } from "../../core/clock.mjs";

const program = (name = "p") => new ProgramState(name);

const ports = () => ({
  command: { run: async () => ({ stdout: "", stderr: "", exitCode: 0 }) },
  control: { unpark: async () => null },
  relay: { review: async () => ({ ok: true }) },
  fork: {
    fork: async (name, _brief, role) => ({ name, role, steered: false, started: true }),
    join: async () => null,
  },
  fission: { fission: async () => ({ ok: false, rejection: "InvalidOrigin" }) },
  devops: { investigate: async () => "" },
});

// 根 §3.3: a receipt names the program it ran, so a surface is built with one.
const SOURCE = "class Js extends JsProgram {\n  async run() { return null; }\n}\n";

const tempRoot = async (t, prefix) => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), prefix));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  return dir;
};

const surfaceOf = (role, state = program(), root = "/tmp") =>
  new ProgramSurface({
    name: state.name,
    role,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: ports(),
    root,
    views: new Map(),
  });

test("a role maps to exactly one surface", () => {
  assert.equal(surfaceNameFor(ROLES.MANAGER), "js-manager");
  assert.equal(surfaceNameFor(ROLES.CODER), "js-worker");
  assert.equal(surfaceNameFor(ROLES.DEVOPS), "js-worker");
  assert.equal(surfaceNameFor(ROLES.PLAN), "plan");
  assert.throws(() => surfaceNameFor("planner"), /unknown role/);
});

test("manager has no file access and no command execution", () => {
  const members = surfaceOf(ROLES.MANAGER).surface();
  for (const name of ["fork", "join", "review", "ask", "park", "unpark"]) {
    assert.equal(typeof members[name], "function", name);
  }
  // Manager NEVER edits, runs or greps (spec §2.1); it does not even read files
  // (spec §2.4, §8). `js-review` is the separate read-only surface.
  for (const name of [
    "cat",
    "stat",
    "ls",
    "write",
    "mkdir",
    "rm",
    "mv",
    "where",
    "whereAll",
    "call",
    "fission",
    "read",
    "grep",
    "glob",
    "bash",
    "edit",
  ]) {
    assert.equal(members[name], undefined, name);
  }
  assert.deepEqual(Object.keys(members).sort(), [
    "ask",
    "askPreviousScope",
    "fork",
    "join",
    "negotiate",
    "park",
    "review",
    "unpark",
  ]);
});

test("worker keeps call, fission, fork and join; it has no ask or review", () => {
  for (const role of [ROLES.CODER, ROLES.DEVOPS]) {
    const members = surfaceOf(role).surface();
    assert.equal(typeof members.call, "function", role);
    assert.equal(typeof members.fission, "function", role);
    // §3.1 marks fork/join for `js-worker` as well as `js-manager`.
    assert.equal(typeof members.fork, "function", `${role}: fork`);
    assert.equal(typeof members.join, "function", `${role}: join`);
    // 根 §3.1 marks both channels absent for `js-worker`: a worker's turn
    // ending is its report, and the ledger is the Manager's.
    assert.equal(members.ask, undefined, `${role}: ask`);
    assert.equal(members.review, undefined, `${role}: review`);
  }
});

test("plan sees its own members, including the unpark its ask needs", () => {
  const members = surfaceOf(ROLES.PLAN).surface();
  // 根 §3.1 marks `this.park` absent for Plan; `this.ask` parks internally.
  // 根 §2.2: the answer arrives as `this.unpark(name, answer)`, so `plan` owns
  // that member even though it has no separate park.
  assert.deepEqual(Object.keys(members).sort(), ["ask", "cat", "devops", "review", "unpark", "write"]);
});

test("plan's draft is read-only until the tenure's first review (spec 5.5)", async (t) => {
  const dir = await tempRoot(t, "compose-plan-");
  const locked = surfaceOf(ROLES.PLAN, program("plan-1"), dir).surface();
  let blocked = null;
  try {
    await locked.write("draft.md", "x");
  } catch (error) {
    blocked = error;
  }
  assert.equal(blocked?.code, "DRAFT_READ_ONLY");
  // The lock is per program run: a program that has not reviewed yet is
  // read-only, and one that has reviewed may write (spec §2.2).
  const reviewed = surfaceOf(ROLES.PLAN, program("plan-2"), dir);
  const plan = reviewed.surface();
  await plan.review([{ key: "open", value: "x" }]);
  await plan.write("draft.md", "x");
  assert.equal(await plan.cat("draft.md"), "x");
});

test("a successor tenure starts read-only again (spec 5.5)", async (t) => {
  const dir = await tempRoot(t, "compose-plan-");
  const reviewed = surfaceOf(ROLES.PLAN, program("plan-3"), dir);
  await reviewed.surface().review([{ key: "open", value: "x" }]);
  assert.equal(reviewed.ledger.reviewCount, 1);
  // A tenure opened by a relay starts with no review of its own: read-only.
  reviewed.ledger.openSuccessor();
  assert.equal(reviewed.ledger.reviewCount, 0);
  let blocked = null;
  try {
    await reviewed.surface().write("draft.md", "y");
  } catch (error) {
    blocked = error;
  }
  assert.equal(blocked?.code, "DRAFT_READ_ONLY");
});

test("park returns a receipt and a second park is refused", () => {
  const surface = surfaceOf(ROLES.CODER);
  const receipt = surface.park({ why: "test" });
  assert.equal(receipt.state, "parked");
  assert.equal(receipt.reason, "explicit");
  assert.deepEqual(receipt.payload, { why: "test" });
  assert.throws(() => surface.park(null), /already has a parked site/);
});

test("ask parks the program with the question as payload, and only in Plan S1", () => {
  // 根 §2.2: the question belongs to Plan's S1. A Manager that tries it reads the
  // named refusal and nothing parks.
  const manager = surfaceOf(ROLES.MANAGER);
  assert.throws(
    () => manager.surface().ask("which file?"),
    (error) => error.code === "ASK_FORBIDDEN_OUTSIDE_S1",
  );
  assert.equal(manager.program.state, "running");

  // In S1 the member parks exactly like an explicit park, and the receipt says
  // the turn must end.
  const plan = surfaceOf(ROLES.PLAN, program("p-ask"));
  const receipt = plan.surface().ask("which file?");
  assert.equal(receipt.state, "parked");
  assert.equal(receipt.reason, "ask");
  assert.deepEqual(receipt.payload, { question: "which file?" });
  assert.equal(receipt.interrupt, true);
});

test("a terminal program cannot park", () => {
  const state = program("done");
  state.finish("completed", 1);
  const surface = surfaceOf(ROLES.CODER, state);
  assert.throws(() => surface.park(null), /completed/);
});

test("the member lists match the specification", () => {
  assert.deepEqual(SURFACE_MEMBERS["js-review"], ["cat", "stat", "ls", "where", "whereAll"]);
  assert.ok(!SURFACE_MEMBERS["js-review"].includes("write"));
  assert.ok(SURFACE_MEMBERS.plan.includes("devops"));
  assert.ok(!SURFACE_MEMBERS.plan.includes("call"));
});

test("the deadline is fixed at admission and the receipt reports what is left", () => {
  const clock = fakeClock(0);
  const state = program("d");
  const surface = new ProgramSurface({
    name: state.name,
    role: ROLES.CODER,
    program: state,
    clock,
    source: SOURCE,
    ports: ports(),
    root: "/tmp",
    views: new Map(),
    intent: "abc", // 3 bytes -> 150 ms
  });
  clock.advance(40);
  assert.equal(surface.receipt().remainingWaitMs, 110);
  // A park cancels the run's deadline; the receipt says so instead of 0 by
  // construction.
  const parked = surface.park({ why: "x" });
  assert.equal(parked.remainingWaitMs, 0);
  assert.equal(state.deadline.cancelled, true);
});

test("settle finishes the run and retires the name after its operations drain", async () => {
  const registry = new ProgramRegistry();
  const state = registry.begin("s");
  const surface = new ProgramSurface({
    name: state.name,
    role: ROLES.CODER,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: ports(),
    root: "/tmp",
    views: new Map(),
    registry,
  });
  const running = state.beginOperation();
  let settled = false;
  const closing = surface.settle("completed", 7).then(() => {
    settled = true;
  });
  await Promise.resolve();
  // The body ended, but a started operation still runs: the name stays live.
  assert.equal(state.state, "completed");
  assert.equal(settled, false);
  assert.equal(registry.live("s"), state);
  state.endOperation(running);
  await closing;
  assert.equal(settled, true);
  assert.equal(registry.live("s"), null);
  assert.equal(registry.begin("s").state, "running");
});

// ------------------------------------------------ Scope snapshot queries (§6.2)

const scopePorts = (answer = () => ({ ok: true, kind: "negotiate", remaining: 2 })) => ({
  ...ports(),
  scope: { ask: (member) => answer(member) },
});

const withPorts = (role, extra, state = program()) =>
  new ProgramSurface({
    name: state.name,
    role,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: { ...ports(), ...extra },
    root: "/tmp",
    views: new Map(),
  });

test("both booking surfaces carry the two exempt snapshot members", () => {
  for (const [surface, role] of [
    ["js-manager", ROLES.MANAGER],
    ["js-worker", ROLES.CODER],
    ["js-worker", ROLES.DEVOPS],
  ]) {
    for (const member of ["negotiate", "askPreviousScope"]) {
      assert.ok(SURFACE_MEMBERS[surface].includes(member), `${surface}: ${member}`);
      assert.equal(typeof surfaceOf(role).surface()[member], "function", `${role}: ${member}`);
    }
  }
  // Neither the read-only evaluation face nor Plan books Scope, so neither
  // carries the queries.
  for (const surface of ["js-review", "plan"]) {
    assert.ok(!SURFACE_MEMBERS[surface].includes("negotiate"), surface);
    assert.ok(!SURFACE_MEMBERS[surface].includes("askPreviousScope"), surface);
  }
});

test("a snapshot member answers synchronously from the scope port", async () => {
  const asked = [];
  const surface = withPorts(ROLES.MANAGER, scopePorts((member) => {
    asked.push(member);
    return { ok: true, kind: member, remaining: 2, goal: "g" };
  }));
  const members = surface.surface();
  const answer = members.negotiate();
  // A promise here would be a park, not a snapshot: the model must be able to
  // read the budget and go on inside the same program.
  assert.equal(typeof answer.then, "undefined");
  assert.equal(answer.remaining, 2);
  assert.equal(members.askPreviousScope().kind, "askPreviousScope");
  assert.deepEqual(asked, ["negotiate", "askPreviousScope"]);
  assert.equal(surface.program.state, "running", "a query never parks the program");
});

test("a refused snapshot stops the program with the refusal's own code", () => {
  for (const refusal of [
    { ok: false, code: "SCOPE_SNAPSHOT_BUSY", message: "a call is still settling" },
    { ok: false, code: "SCOPE_UNINITIALIZED", message: "no scope for session" },
  ]) {
    const members = withPorts(ROLES.CODER, scopePorts(() => refusal)).surface();
    let thrown = null;
    try {
      members.negotiate();
    } catch (error) {
      thrown = error;
    }
    assert.equal(thrown?.code, refusal.code);
    assert.equal(thrown?.details.refusal.code, refusal.code);
  }
});

test("a surface without a scope port refuses the query at the call, not at build", () => {
  const surface = surfaceOf(ROLES.MANAGER);
  const members = surface.surface();
  assert.equal(typeof members.fork, "function", "the rest of the surface still exists");
  let thrown = null;
  try {
    members.negotiate();
  } catch (error) {
    thrown = error;
  }
  assert.equal(thrown?.code, "MISSING_PORT");
  assert.equal(thrown?.details.port, "scope");
});

test("a snapshot member is exempt: it works with no budget and no annotation", async () => {
  // The port is core's own hooks, so this is the real admission rule and not a
  // stand-in: an exempt member answers before any reset established a budget.
  const { ScopeHooks } = await import("../../core/scope/index.mjs");
  const hooks = new ScopeHooks();
  hooks.open({ sessionId: "s" });
  const members = withPorts(ROLES.MANAGER, {
    scope: { ask: (member) => hooks.ask(member, "s") },
  }).surface();
  const answer = members.negotiate();
  assert.equal(answer.ok, true);
  assert.equal(answer.established, false);
  assert.equal(answer.mustReset, true, "an unconfirmed budget is reported as needing a reset");
  assert.equal(hooks.ask("negotiate", "s").remaining, answer.remaining, "the query booked nothing");
});
