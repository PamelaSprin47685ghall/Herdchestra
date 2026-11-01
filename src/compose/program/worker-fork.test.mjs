// `fork`/`join` on `js-worker` (根 AGENTS.md §3.1, §4.1).
//
// §3.1 marks `fork`/`join` for `js-manager` AND `js-worker`; §4.1 says a
// worker's `fork` fills the worker's own roster, so its children are its own and
// the Manager's `join` never sees them. These tests exercise the members a
// worker's program actually calls, not the member table: the identity travels
// through the brief, the port receives the resolved role, and `join` answers
// whatever the roster answers — never an invented record.
//
// The rosters here are fakes with the contract the real ones keep: `fork` adds a
// name to one roster, `join` takes the first report from that same roster, and
// nothing crosses between two rosters.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { ProgramSurface, ROLES, SURFACE_MEMBERS } from "./surface.mjs";
import { ProgramState } from "../../core/program.mjs";
import { fakeClock } from "../../core/clock.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

// 根 §3.3: a receipt names the program it ran, so every surface here is built
// with one. It is never executed: these tests call members directly.
const SOURCE = "class Js extends JsProgram {\n  async run() { return null; }\n}\n";


// One roster: a name set plus a report queue. `fork` records what it was asked
// for, so a test can see the role the port received rather than the role it
// might have guessed.
const roster = (label) => {
  const started = [];
  const reports = [];
  return {
    label,
    started,
    reports,
    port: {
      fork: async (name, brief, role) => {
        started.push({ name, brief, role });
        return { name, steered: false, started: true };
      },
      join: async () => (reports.length === 0 ? null : reports.shift()),
    },
    report: (entry) => reports.push(entry),
  };
};

const workerSurface = (role, rosterPort, name = "w1") => {
  const state = new ProgramState(name);
  const surface = new ProgramSurface({
    name,
    role,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: {
      command: { run: async () => ({ stdout: "", stderr: "", exitCode: 0 }) },
      control: { unpark: async () => null },
      fork: rosterPort,
      fission: { fission: async () => ({ ok: false, rejection: "InvalidOrigin" }) },
    },
    root: "/tmp",
    views: new Map(),
  });
  return { state, members: surface.surface() };
};

test("§3.1: both worker identities carry fork and join as program members", () => {
  for (const role of [ROLES.CODER, ROLES.DEVOPS]) {
    const name = role === ROLES.DEVOPS ? "js-worker" : "js-worker";
    assert.ok(SURFACE_MEMBERS[name].includes("fork"), `${role}: fork in SURFACE_MEMBERS`);
    assert.ok(SURFACE_MEMBERS[name].includes("join"), `${role}: join in SURFACE_MEMBERS`);
  }
  // Manager keeps them too, and keeps `review`, which a worker lacks. A worker
  // also has no `ask`: 根 §3.1 marks the question channel absent for
  // `js-worker`; only top-level programs ask.
  assert.ok(SURFACE_MEMBERS["js-manager"].includes("fork"));
  assert.equal(SURFACE_MEMBERS["js-worker"].includes("review"), false);
  assert.equal(SURFACE_MEMBERS["js-worker"].includes("ask"), false);
  assert.equal(SURFACE_MEMBERS.plan.includes("fork"), false);
});

test("a worker's fork sends the brief's declared role to its own roster", async () => {
  const mine = roster("worker");
  const { members } = workerSurface(ROLES.CODER, mine.port);
  const receipt = await members.fork("child", "role: devops — run the suite.");
  assert.deepEqual(receipt, { name: "child", steered: false, started: true });
  assert.deepEqual(mine.started, [{ name: "child", brief: "role: devops — run the suite.", role: "devops" }]);
});

test("a worker's fork refuses an undeclared brief before the port runs", async () => {
  const mine = roster("worker");
  const { members } = workerSurface(ROLES.DEVOPS, mine.port);
  await assert.rejects(async () => {
    await members.fork("child", "run the suite.");
  }, (error) => error.details?.reason === "UNDECLARED_ROLE");
  assert.deepEqual(mine.started, [], "the port was never asked to start anything");
});

test("a worker's join answers its own roster and never invents a record", async () => {
  const mine = roster("worker");
  const { members } = workerSurface(ROLES.CODER, mine.port);
  assert.equal(await members.join(), null);
  mine.report({ name: "child", text: "done" });
  assert.deepEqual(await members.join(), { name: "child", text: "done" });
  assert.equal(await members.join(), null);
});

test("a worker's fork and join share one roster, and the Manager's is untouched", async () => {
  const worker = roster("worker");
  const manager = roster("manager");
  const child = workerSurface(ROLES.CODER, worker.port, "w1");
  const boss = workerSurface(ROLES.MANAGER, manager.port, "m1");

  await child.members.fork("c1", "role: coder — edit.");
  assert.deepEqual(worker.started.map((entry) => entry.name), ["c1"]);
  assert.deepEqual(manager.started, [], "the Manager's roster never received the worker's child");

  worker.report({ name: "c1", text: "edited" });
  assert.deepEqual(await child.members.join(), { name: "c1", text: "edited" });
  // The Manager still has nothing to join: the report belonged to the worker.
  assert.equal(await boss.members.join(), null);
});

test("property: every fork a worker makes lands in its roster with the declared role", async () => {
  const roleOf = (value) => (value === "devops" ? "devops" : "coder");
  await fc.assert(
    fc.asyncProperty(
      fc.array(fc.record({ name: fc.stringMatching(/^[a-z][a-z0-9-]{0,8}$/), role: fc.constantFrom("coder", "devops") }), {
        maxLength: 6,
      }),
      fc.constantFrom(ROLES.CODER, ROLES.DEVOPS),
      async (requests, callerRole) => {
        const mine = roster("worker");
        const other = roster("other");
        const { members } = workerSurface(callerRole, mine.port);
        // Names must be distinct: a repeated name is the steer case, which the
        // roster decides, and this property is about where a start lands.
        const unique = [...new Map(requests.map((r) => [r.name, r])).values()];
        for (const request of unique) {
          await members.fork(request.name, `role: ${request.role} — do the work.`);
        }
        assert.deepEqual(mine.started.map((entry) => entry.name), unique.map((entry) => entry.name));
        assert.deepEqual(mine.started.map((entry) => entry.role), unique.map((entry) => roleOf(entry.role)));
        assert.deepEqual(other.started, []);
      },
    ),
    { ...fcRuns(40) },
  );
});

test("property: join returns exactly the reports the roster still holds, in order", async () => {
  await fc.assert(
    fc.asyncProperty(fc.array(fc.string({ minLength: 1, maxLength: 12 }), { maxLength: 6 }), async (texts) => {
      const mine = roster("worker");
      const { members } = workerSurface(ROLES.CODER, mine.port);
      for (const [index, text] of texts.entries()) mine.report({ name: `c${index}`, text });
      const seen = [];
      for (let index = 0; index < texts.length; index += 1) seen.push(await members.join());
      assert.deepEqual(
        seen.map((record) => record?.text),
        texts,
      );
      // The roster is empty now: join says so instead of repeating a report.
      assert.equal(await members.join(), null);
    }),
    { ...fcRuns(40) },
  );
});

test("property: a brief that names no single identity never reaches a worker's roster", async () => {
  const ambiguousOrMissing = fc.oneof(
    fc.string({ maxLength: 24 }).filter((text) => !/\b(coder|devops)\b/i.test(text)),
    fc.constant("role: coder, devops — do it."),
    fc.constant(""),
    fc.constant("   "),
  );
  await fc.assert(
    fc.asyncProperty(ambiguousOrMissing, fc.constantFrom(ROLES.CODER, ROLES.DEVOPS), async (brief, callerRole) => {
      const mine = roster("worker");
      const { members } = workerSurface(callerRole, mine.port);
      let refused = false;
      try {
        await members.fork("child", brief);
      } catch (error) {
        refused = true;
        assert.equal(error.code, "INVALID_ARGUMENT");
      }
      assert.equal(refused, true, JSON.stringify(brief));
      assert.deepEqual(mine.started, []);
    }),
    { ...fcRuns(40) },
  );
});
