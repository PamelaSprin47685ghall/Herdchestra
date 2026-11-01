import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { ProgramSurface, ROLES } from "./surface.mjs";
import { ProgramState } from "../../core/program.mjs";
import { fakeClock } from "../../core/clock.mjs";

const root = async () => fs.mkdtemp(path.join(os.tmpdir(), "compose-"));

const ports = (overrides = {}) => ({
  command: {
    run: async (command, { onData }) => {
      onData?.({ stream: "stdout", chunk: `ran:${command}\n` });
      return { stdout: `ran:${command}\n`, stderr: "", exitCode: 0 };
    },
  },
  control: { unpark: async ({ name }) => ({ resumed: name }) },
  // `PORT_METHODS.relay` names the three host steps, not a `review` method.
  relay: {
    handOver: async (decision) => ({ ok: true, retired: true, successor: "m1", barrier: true, decision }),
    startManager: async (draft) => ({ ok: true, draft }),
    askOperator: async (question) => ({ ok: true, question }),
  },
  fork: { fork: async (name, _brief, role) => ({ name, role, steered: false, started: true }), join: async () => null },
  fission: { fission: async () => ({ ok: false, rejection: "InvalidOrigin" }) },
  devops: { investigate: async (intent) => `facts:${intent}` },
  draft: { read: async () => "draft bytes" },
  ...overrides,
});

// 根 §3.3: a receipt names the program it ran, so every surface here is built
// with one. It is never executed: these tests call members directly.
const SOURCE = "class Js extends JsProgram {\n  async run() { return null; }\n}\n";

const surfaceOf = (role, state, dir, extra = {}) =>
  new ProgramSurface({
    name: state.name,
    role,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: ports(extra),
    root: dir,
    views: new Map(),
  });

test("cat, write and where work end to end on a real directory", async () => {
  const dir = await root();
  const state = new ProgramState("w1");
  const members = surfaceOf(ROLES.CODER, state, dir).surface();
  const written = await members.write("notes.txt", "alpha\nbeta\n");
  assert.equal(written.bytes, 11);
  const text = await members.cat("notes.txt");
  assert.equal(text, "alpha\nbeta\n");
  assert.deepEqual(members.where(text, "beta"), [6, 10]);
  assert.deepEqual(members.whereAll(text, "\n"), [[5, 6], [10, 11]]);
  const info = await members.stat("notes.txt");
  assert.equal(info.exists, true);
  assert.equal(info.size, 11);
  assert.deepEqual(await members.stat("missing.txt"), { exists: false });
  assert.deepEqual(await members.ls(""), ["notes.txt"]);
});

test("an ambiguous where parks with the candidates payload", async () => {
  const dir = await root();
  const state = new ProgramState("w2");
  const members = surfaceOf(ROLES.CODER, state, dir).surface();
  const receipt = members.where("x\ny\nx\n", "x");
  assert.equal(receipt.state, "parked");
  assert.equal(receipt.reason, "explicit");
  assert.equal(receipt.payload.needle, "x");
  assert.equal(receipt.payload.candidates.length, 2);
  assert.equal(state.state, "parked");
});

test("the review surface parks with the same diagnostics as the worker", async () => {
  const dir = await root();
  const surface = surfaceOf(ROLES.MANAGER, new ProgramState("m3"), dir);
  const review = surface.reviewSurface();
  // A payload handed straight to the park channel arrives as a site with no
  // `payload`, which silently loses `needle`/`candidates`/`nearest`.
  const ambiguous = review.where("y\ny\n", "y");
  assert.equal(ambiguous.state, "parked");
  assert.equal(ambiguous.payload.needle, "y");
  assert.equal(ambiguous.payload.candidates.length, 2);

  const missing = surfaceOf(ROLES.MANAGER, new ProgramState("m4"), dir).reviewSurface().where("abc\n", "zzz");
  assert.equal(missing.state, "parked");
  assert.deepEqual(missing.payload.candidates, []);
  assert.ok(missing.payload.nearest !== null, "a zero match carries its nearest line");
});

test("an explicit park receipt has no delta and carries the payload", async () => {
  const dir = await root();
  // The park path reaches the receipt with no argument; a `delta` that is only
  // bound inside another method made this a ReferenceError.
  const receipt = surfaceOf(ROLES.CODER, new ProgramState("w7"), dir).surface().park({ q: 1 });
  assert.equal(receipt.state, "parked");
  assert.deepEqual(receipt.payload, { q: 1 });
  assert.equal(receipt.delta, "");
});

test("a missing file is a refusal with the posix code", async () => {
  const dir = await root();
  const members = surfaceOf(ROLES.CODER, new ProgramState("w3"), dir).surface();
  await assert.rejects(() => members.cat("nope.txt"), (error) => error.code === "ENOENT");
});

test("every write replaces the target atomically; no lock across writes", async () => {
  const dir = await root();
  const members = surfaceOf(ROLES.CODER, new ProgramState("w5"), dir).surface();
  await members.write("twice.txt", "first\n");
  const second = await members.write("twice.txt", "second\n");
  assert.ok(second.path.endsWith("twice.txt"));
  assert.equal(await members.cat("twice.txt"), "second\n");
});

test("call drains, runs through the port and reports the command", async () => {
  const dir = await root();
  const state = new ProgramState("w4");
  const members = surfaceOf(ROLES.DEVOPS, state, dir).surface();
  const receipt = await members.call("git status");
  assert.deepEqual(receipt, { stdout: "ran:git status\n", stderr: "", exitCode: 0 });
  assert.equal(state.pendingOperations, 0);
});

test("call waits for earlier operations, reports waiting, and refuses once finished", async () => {
  const dir = await root();
  const state = new ProgramState("w6");
  const seen = [];
  const surface = surfaceOf(ROLES.DEVOPS, state, dir, {
    command: {
      run: async (command) => {
        seen.push(state.state);
        return { stdout: command, stderr: "", exitCode: 0 };
      },
    },
  });
  const members = surface.surface();
  // An earlier SDK operation is still open: the command must not start yet.
  const earlier = state.beginOperation();
  let started = false;
  const pending = members.call("later").then(() => {
    started = true;
  });
  // Several turns: a command that skips the drain starts within them.
  for (let turn = 0; turn < 3; turn += 1) await new Promise((resolve) => setImmediate(resolve));
  assert.equal(started, false, "the command waited for the earlier operation");
  assert.deepEqual(seen, [], "the command did not reach the port");
  state.endOperation(earlier);
  await pending;
  assert.equal(started, true);
  // The program was `waiting` while its command ran.
  assert.deepEqual(seen, ["waiting"]);
  assert.equal(state.state, "running");

  // A program that has finished takes no new command.
  state.finish("completed", 1);
  await assert.rejects(() => members.call("too late"), /completed/);
});

test("port members pass through without inventing an outcome", async () => {
  const dir = await root();
  const manager = surfaceOf(ROLES.MANAGER, new ProgramState("m1"), dir).surface();
  // §4.1: two arguments. The worker's identity comes from the brief's explicit
  // declaration, and the port receives it resolved.
  assert.deepEqual(await manager.fork("coder-1", "role: coder. Fix the parser."), {
    name: "coder-1",
    role: "coder",
    steered: false,
    started: true,
  });
  assert.deepEqual(await manager.fork("d1", "role: devops. Run the suite."), {
    name: "d1",
    role: "devops",
    steered: false,
    started: true,
  });
  // A brief that names no identity, or both, is refused before the port runs:
  // §2.1 forbids starting a subagent whose identity is not stated.
  await assert.rejects(async () => { await manager.fork("w1", "Fix the parser."); }, (error) => {
    assert.equal(error.code, "INVALID_ARGUMENT");
    assert.equal(error.details.reason, "UNDECLARED_ROLE");
    return true;
  });
  await assert.rejects(async () => { await manager.fork("w2", "role: coder, devops. Do it."); }, (error) => {
    assert.equal(error.details.reason, "AMBIGUOUS_ROLE");
    return true;
  });
  assert.equal(await manager.join(), null);
  // 根 §2.2: a Manager's first empty review is its own verification, not a
  // question — it never waits for a human, and no relay port is reached.
  const first = await manager.review([], undefined);
  assert.equal(first.kind, "confirm");
  assert.equal(first.answer, undefined);
  assert.deepEqual(await manager.unpark("w2", [1, 2]), { resumed: "w2" });

  const plan = surfaceOf(ROLES.PLAN, new ProgramState("p1"), dir).surface();
  assert.equal(await plan.devops("check"), "facts:check");
});

test("js-review reads and never writes", () => {
  const dir = "/tmp";
  const surface = surfaceOf(ROLES.MANAGER, new ProgramState("m2"), dir);
  const review = surface.reviewSurface();
  assert.equal(review.write, undefined);
  assert.equal(review.rm, undefined);
  assert.equal(review.call, undefined);
  assert.equal(typeof review.cat, "function");
});

// --------------------------------------------- documented signatures (§3.1, §3.4)

test("where takes the content, not a path: every §3.4 example shape works", async () => {
  const dir = await root();
  const state = new ProgramState("w-doc");
  const members = surfaceOf(ROLES.CODER, state, dir).surface();
  const source = "export const timeoutMs = 30000;\nconst batchSize = 10;\n";
  await members.write("src.mjs", source);
  const text = await members.cat("src.mjs");
  // §3.4 examples 1, 2, 3 and 8: `this.where(source, needle)` on `cat` output.
  assert.deepEqual(members.where(text, "export const timeoutMs = 30000;\n"), [0, 32]);
  assert.deepEqual(members.whereAll(text, "const"), [[7, 12], [32, 37]]);
  // A path in the first position is still a string, so it is content: the
  // needle does not occur in it and the program parks with the zero-match
  // diagnostics (that is what §3.2 promises for an absent needle). A non-string
  // first argument is the argument error, and its message names the rule.
  const parked = members.where("src.mjs", "timeoutMs");
  assert.equal(parked.state, "parked");
  assert.deepEqual(parked.payload.candidates, []);
  assert.equal(parked.payload.needle, "timeoutMs");
  assert.throws(
    () => members.whereAll(42, "x"),
    (error) => error.code === "INVALID_ARGUMENT",
  );
});

test("stat answers exactly the four documented fields on both faces", async () => {
  const dir = await root();
  await fs.writeFile(path.join(dir, "a.txt"), "abc");
  await fs.mkdir(path.join(dir, "sub"));
  for (const role of [ROLES.CODER, ROLES.MANAGER]) {
    const state = new ProgramState(`stat-${role}`);
    const surface = surfaceOf(role, state, dir);
    const members = role === ROLES.MANAGER ? surface.reviewSurface() : surface.surface();
    const file = await members.stat("a.txt");
    assert.deepEqual(Object.keys(file).sort(), ["exists", "isDir", "mtime", "size"], role);
    assert.equal(file.exists, true);
    assert.equal(file.size, 3);
    assert.equal(file.isDir, false);
    assert.equal(typeof file.mtime, "number");
    assert.equal((await members.stat("sub")).isDir, true, role);
    // §3.4 example 5: a missing path is a fact a program branches on.
    assert.deepEqual(await members.stat("missing.txt"), { exists: false });
  }
});
