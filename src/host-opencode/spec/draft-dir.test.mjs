// Plan's draft directory is prepared before anything can write into it (§2.2).
//
// `atomicWriteFile` (`core/fs.mjs`) creates its temp file INSIDE the target's
// directory, so the first `write` to `plans/<session>.md` fails as
// `ENOENT` when that directory does not exist. The message then names a temp
// file the Plan never asked for, in a directory it cannot create: Plan has no
// `mkdir`, and its only file members are `cat` and `write`.
//
// These are behavioural tests over the real filesystem, because the defect was
// a filesystem fact that unit-level stubs cannot express: a fake port that never
// touches disk cannot fail to create a directory.
//
//   1. A fresh root — no `.Herdchestra` at all — gets its draft directory made
//      by `makeDraftPort`, and a real atomic write then succeeds.
//   2. A directory that cannot be prepared is reported as `DRAFT_DIR_UNWRITABLE`
//      by the port, and a Plan surface refuses at BUILD time with that reason
//      instead of failing later on a write.
//   3. The port never invents a draft: `read()` on a missing file is `""`.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { atomicWriteFile } from "../../core/fs.mjs";
import { ROLES } from "../../compose/program/surface.mjs";
import { makeDraftPort, planDraftPath, PLAN_DRAFT_DIR } from "./program-ports.mjs";
import { makeSurface } from "./surface-factory.mjs";
import { REFUSALS } from "./refusals.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

const tempRoot = () => mkdtempSync(path.join(tmpdir(), "herd-draftdir-"));

// A file-safe session name, as the adapter derives one (`safeName`): the port
// joins it into a path and never sanitises it again.
const nameArb = fc.stringMatching(/^[A-Za-z0-9._-]{1,24}$/);

test("a fresh workspace gets its draft directory before the first write", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));

  // Nothing exists yet: no `.Herdchestra`, no `plans`.
  const port = makeDraftPort({ root, name: "fresh" });
  assert.equal(port.dirFailure(), null, "a writable root prepares its directory");

  const file = port.path();
  assert.equal(file, planDraftPath(root, "fresh"));
  assert.equal(path.dirname(file), path.join(root, PLAN_DRAFT_DIR));
  // Root §1.1 item 3: the draft is the operator's output, visible in the
  // workspace, never inside the adapter's own state directory.
  assert.equal(PLAN_DRAFT_DIR, "plans");
  assert.equal(file.startsWith(path.join(root, "plans") + path.sep), true);
  assert.equal(file.includes(".Herdchestra"), false);

  // The real atomic write the Plan member performs: it needs the directory to
  // exist, and now it does.
  const written = await atomicWriteFile(file, "# draft\n");
  assert.equal(written.ok, true, JSON.stringify(written));
  assert.equal(await port.read(), "# draft\n");
});

test("the draft directory is prepared for any session name, on a root that has nothing", async (t) => {
  // Property: for every legal session name and a root with no state at all, the
  // port prepares its directory and a write through the real atomic path
  // succeeds. The defect was name-independent, so the test is too.
  await fc.assert(
    fc.asyncProperty(nameArb, async (name) => {
      const root = tempRoot();
      try {
        const port = makeDraftPort({ root, name });
        assert.equal(port.dirFailure(), null);
        const written = await atomicWriteFile(port.path(), name);
        assert.equal(written.ok, true, JSON.stringify(written));
        assert.equal(await port.read(), name);
      } finally {
        rmSync(root, { recursive: true, force: true });
      }
    }),
    { ...fcRuns(25) },
  );
});

test("a directory that cannot be prepared is reported, not left as an ENOENT at write time", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  // A FILE where the directory must go: `mkdir -p` fails with ENOTDIR, which is
  // a real, nameable condition rather than a permissions guess.
  writeFileSync(path.join(root, PLAN_DRAFT_DIR.split(path.sep)[0]), "not a directory");

  const port = makeDraftPort({ root, name: "blocked" });
  const failure = port.dirFailure();
  assert.ok(failure !== null, "the port states the failure it could not avoid");
  assert.equal(failure.reason, "DRAFT_DIR_UNWRITABLE");
  assert.equal(typeof failure.directory, "string");

  // The port still reads: a missing draft is legitimately "".
  assert.equal(await port.read(), "");

  // A Plan surface refuses at BUILD time, with that reason, before any program
  // can run. This is the difference between a stated failure and a mystery.
  const built = makeSurface({
    role: "plan",
    name: "call-1",
    program: null,
    session: { location: { directory: root } },
    source: null,
    ports: {
      // The real shapes compose requires (`PORT_METHODS`): a stub that lacks a
      // method is refused by `requirePorts` before the draft gate is reached.
      relay: { handOver: async () => null, startManager: async () => null, askOperator: async () => null },
      devops: { investigate: async () => null },
      draft: port,
      control: {},
      worker: {},
    },
    views: new Map(),
    guard: () => null,
  });
  assert.equal(built.surface, null);
  assert.equal(built.refusal.rejection, REFUSALS.DRAFT_DIR_UNWRITABLE);
  assert.equal(typeof built.refusal.message, "string");
  assert.equal(built.refusal.message.length > 0, true);
  // Nothing is left that a caller could mistake for a runnable surface.
  assert.deepEqual(Object.keys(built.members), []);
});

test("a Manager surface is unaffected by an unprepared draft directory", async (t) => {
  const root = tempRoot();
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(path.join(root, PLAN_DRAFT_DIR.split(path.sep)[0]), "not a directory");
  const port = makeDraftPort({ root, name: "blocked" });
  assert.notEqual(port.dirFailure(), null);

  // Only Plan writes a draft: the Manager has no draft port and no such gate.
  const built = makeSurface({
    role: "manager",
    name: "call-2",
    program: null,
    session: { location: { directory: root } },
    // Compose refuses a surface without the program it runs, so the fixture
    // states it, exactly as the executor does.
    source: "return 1;",
    ports: {
      relay: { handOver: async () => null, startManager: async () => null, askOperator: async () => null },
      control: {},
      worker: { fork: async () => null, join: async () => null },
      // `requirePorts` runs BEFORE `makeSurface` derives `fork`/`join` from
      // `worker`, so the fixture states them itself.
      fork: { fork: async () => null, join: async () => null },
      join: { join: async () => null },
      // The Manager's two exempt Scope queries (§6.2): synchronous by contract.
      scope: { ask: (member) => ({ ok: true, kind: member, remaining: 0 }) },
    },
    views: new Map(),
    guard: () => null,
  });
  assert.notEqual(built.surface, null, "a Manager is not gated on Plan's draft directory");
});
