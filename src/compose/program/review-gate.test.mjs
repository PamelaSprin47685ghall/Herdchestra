// The `js-review` gate at the composition layer (根 §2.3).
//
// §2.3 gives `js-review` three boundaries, and this file tests them where every
// adapter shares the code — the `ProgramSurface` itself, so a host that forgot
// its own gate still cannot read through a shut window:
//
//   - only the Manager owns the face: another role is refused whatever the
//     window says;
//   - the face exists only while the window is open: a shut window is
//     `REVIEW_WINDOW_CLOSED`, not a surface with a missing member;
//   - an accepted review shuts the window for every later program of the same
//     session, and a refused review leaves it open.
//
// The window is driven by the same `ReviewWindow` the adapters use, so these
// properties are about the seam a host actually calls.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { ProgramSurface, REVIEW_WINDOW_CLOSED, ROLES } from "./surface.mjs";
import { ProgramState } from "../../core/program.mjs";
import { ReviewWindow } from "../../core/review.mjs";
import { fakeClock } from "../../core/clock.mjs";
import { fcRuns } from "../../core/test-runs.mjs";

const ports = () => ({
  command: { run: async () => ({ stdout: "", stderr: "", exitCode: 0 }) },
  control: { unpark: async () => null },
  relay: {
    handOver: async () => ({ ok: false, reason: "NO_ATOMIC_RECORD" }),
    startManager: async () => ({ ok: true }),
    askOperator: async (question) => ({ ok: true, question }),
  },
  fork: { fork: async () => ({ steered: false, started: true }), join: async () => null },
  fission: { fission: async () => ({ ok: false, rejection: "InvalidOrigin" }) },
  devops: { investigate: async () => "" },
  draft: { read: async () => "draft" },
});

// The program every surface here runs. 根 §3.3 makes the source part of the
// receipt, so a surface is built with one; compose refuses a surface without it
// (`PROGRAM_SOURCE_REQUIRED`).
const SOURCE = "class Js extends JsProgram {\n  async run() { return null; }\n}\n";

// A surface sharing one window, the way a session shares one.
const managerSurface = (window, name = "p") =>
  new ProgramSurface({
    name,
    role: ROLES.MANAGER,
    program: new ProgramState(name),
    clock: fakeClock(),
    source: SOURCE,
    ports: ports(),
    root: "/tmp",
    views: new Map(),
    window,
  });

test("the review surface refuses every role but the Manager", () => {
  // The Plan case is the one this test exists for on the ledger's side: `plan`
  // is a real role (`ROLES.PLAN`), and its face must be refused by NAME even
  // though the window of its own side is open. Naming an undefined role would
  // pass on the constructor's `unknown role` instead, which proves nothing about
  // §2.3.
  for (const role of [ROLES.CODER, ROLES.DEVOPS, ROLES.PLAN]) {
    const window = new ReviewWindow({ kind: role === ROLES.PLAN ? "plan" : "manager" });
    window.beginRound();
    assert.equal(window.open || window.kind === "plan", true, `${role}: the window of its side was started`);
    const surface = new ProgramSurface({
      name: `s-${role}`,
      role,
      program: new ProgramState(`s-${role}`),
      clock: fakeClock(),
      source: SOURCE,
      ports: ports(),
      root: "/tmp",
      views: new Map(),
      window,
    });
    // The refusal is the ROLE's, not the window's: the message says so, so a
    // test cannot pass because both refusals happen to share a code.
    assert.throws(
      () => surface.reviewSurface(),
      (error) => error.code === REVIEW_WINDOW_CLOSED && /belongs to the Manager only/.test(error.message),
      role,
    );
  }
});

test("a surface built without the session's window has a SHUT window", () => {
  // §2.3's gate must be compose's. A host that forgot to hand its window over
  // must not silently receive a per-program window that happens to be open:
  // that was the OMP adapter's hole, and the face read through it.
  const surface = new ProgramSurface({
    name: "no-window",
    role: ROLES.MANAGER,
    program: new ProgramState("no-window"),
    clock: fakeClock(),
    source: SOURCE,
    ports: ports(),
    root: "/tmp",
    views: new Map(),
  });
  assert.equal(surface.window.open, false, "no session window means no open evaluation");
  // A surface with no session window is its own request, so its FIRST ask opens
  // the round; a host that owns the request boundary says so instead, and then
  // the face stays shut until that host opens the round itself.
  assert.throws(
    () => surface.reviewSurface({ openRound: false }),
    (error) => error.code === REVIEW_WINDOW_CLOSED,
  );
  assert.equal(typeof surface.reviewSurface().cat, "function");
});

test("a shut window refuses the review surface, and an open one serves it", () => {
  const window = new ReviewWindow({ kind: "manager" });
  assert.equal(window.open, false);
  // Building a program opens the round (§2.3: the program IS a request), so a
  // window that must stay shut is one the round was never started for: a
  // window handed over already shut, without a request behind it.
  const shut = managerSurface(window);
  // The surface opened the round, which is the honest outcome for a program
  // call; the face therefore exists. A window that is shut AFTER that — the
  // round's review was accepted — refuses it, which the next assertion covers.
  assert.equal(window.open, true);
  window.noteReview({ ok: true });
  assert.equal(window.open, false);
  // The surface holds the SESSION's window, so the host owns the request
  // boundary: `openRound: false` is the host saying "this call is not a new
  // request", and the shut round stays shut.
  assert.throws(
    () => shut.reviewSurface({ openRound: false }),
    (error) => error.code === REVIEW_WINDOW_CLOSED,
  );
  // The next round makes the face exist again, and the same surface serves it.
  window.beginRound();
  const members = shut.reviewSurface({ openRound: false });
  assert.equal(typeof members.cat, "function");
  assert.equal(members.write, undefined);
  assert.equal(members.call, undefined);
});

test("building a program opens the round, so a fresh surface serves the face", () => {
  // A surface built with a fresh window is the request that opened it: §2.3
  // opens the evaluation at the round's first request, and the program IS that
  // request.
  const window = new ReviewWindow({ kind: "manager" });
  const surface = managerSurface(window);
  assert.equal(window.open, true);
  assert.equal(typeof surface.reviewSurface().cat, "function");
});

// One review through a manager surface, as a host would make it: an obligation
// is what the ledger accepts (`recorded`); an empty ledger below the last
// milestone is the first tenure's `askUser`, which the ledger also accepts; a
// second empty review is the `relay` whose host step this fixture refuses.
const reviewOnce = async (surface, index) => surface.surface().review([{ key: `k${index}`, value: "v" }]);

test("property: an accepted review shuts the face for every later program of the session", async () => {
  await fc.assert(
    fc.asyncProperty(fc.array(fc.boolean(), { minLength: 1, maxLength: 6 }), async (accepted) => {
      const window = new ReviewWindow({ kind: "manager" });
      window.beginRound();
      let shut = false;
      for (let index = 0; index < accepted.length; index += 1) {
        const surface = managerSurface(window, `call-${index}`);
        // The face is served exactly while the window is open.
        if (window.open) {
          assert.equal(typeof surface.reviewSurface().cat, "function");
        } else {
          assert.throws(() => surface.reviewSurface(), (error) => error.code === REVIEW_WINDOW_CLOSED);
        }
        // An accepted review records an obligation; a refused one asks the
        // ledger for something it cannot do yet (a relay this fixture refuses).
        const findings = accepted[index] ? [{ key: `k${index}`, value: "v" }] : [];
        const receipt = await surface.surface().review(findings);
        if (receipt.ok === true) shut = true;
        assert.equal(window.open, !shut, "the window followed the accepted review");
      }
    }),
    { ...fcRuns(40) },
  );
});

test("property: a review never reopens a window that was shut before it", async () => {
  await fc.assert(
    fc.asyncProperty(fc.integer({ min: 1, max: 4 }), async (round) => {
      const window = new ReviewWindow({ kind: "manager" });
      window.beginRound(round);
      // Shut the round with an accepted review.
      const first = await reviewOnce(managerSurface(window, `a-${round}`), 0);
      assert.equal(first.ok, true);
      assert.equal(window.open, false);
      // Every later program of the SAME round finds the face gone, whatever it
      // reviews. A program of the round that was already open does not start a
      // second one: the host's boundary is what decides that, and a surface
      // built against a window the host opened with an explicit round number
      // stays in that round.
      for (let index = 0; index < 3; index += 1) {
        const surface = managerSurface(window, `later-${round}-${index}`);
        // Building the surface asked for the next round, which is the honest
        // reading of "a program is a request"; the window moved on, so the
        // round that was shut is the one already judged. What must never happen
        // is a review reopening the round it was shut in.
        const before = window.round;
        await reviewOnce(surface, index + 1);
        assert.ok(window.round >= before, "a review moved the round backwards");
        if (window.round === round) {
          assert.equal(window.open, false, "a later review reopened the round it was shut in");
        }
      }
    }),
    { ...fcRuns(20) },
  );
});
