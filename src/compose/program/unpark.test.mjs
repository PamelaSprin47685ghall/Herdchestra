import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { ProgramSurface, ROLES } from "./surface.mjs";
import { ProgramState } from "../../core/program.mjs";
import { ControlSurface } from "../../core/control.mjs";
import { ProgramRegistry } from "../../core/program.mjs";
import { fakeClock } from "../../core/clock.mjs";

// 根 §3.3: a receipt names the program it ran, so every surface here is built
// with one. It is never executed: these tests call members directly.
const SOURCE = "class Js extends JsProgram {\n  async run() { return null; }\n}\n";

const root = async (t) => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "compose-"));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  return dir;
};

const ports = () => ({
  command: { run: async () => ({ stdout: "", stderr: "", exitCode: 0 }) },
  relay: { review: async () => ({ ok: true }) },
  fork: { fork: async () => ({ steered: false, started: true }), join: async () => null },
  fission: { fission: async () => ({ ok: false, rejection: "InvalidOrigin" }) },
  devops: { investigate: async () => "" },
});

const surfaceOf = (role, state, dir, extra = {}) =>
  new ProgramSurface({
    name: state.name,
    role,
    program: state,
    clock: fakeClock(),
    source: SOURCE,
    ports: { ...ports(), ...extra },
    root: dir,
    views: new Map(),
  });

test("unpark resumes another program through core's control surface", async (t) => {
  const dir = await root(t);
  const registry = new ProgramRegistry();
  const control = new ControlSurface(registry);
  const target = registry.begin("target");
  target.park({ reason: "explicit", payload: { needle: "x" } });

  const controller = new ProgramState("controller");
  const surface = surfaceOf(ROLES.CODER, controller, dir, {
    control: {
      // Forward the request as compose sends it. Overriding `capabilities`
      // here would hide the one fact this test exists to check: the surface
      // hands core the controller program's own set, not an empty one.
      unpark: (request) =>
        control.unpark(request),
    },
  });
  const members = surface.surface();

  // Without observing the target in this request, the resume is stale.
  assert.throws(
    () => members.unpark("target", [1, 2]),
    (error) => error.code === "STALE_CONTROL",
  );

  control.open("controller");
  control.observe("controller", "target");
  const receipt = members.unpark("target", [1, 2]);
  assert.deepEqual(receipt.value, [1, 2]);
  // The answer is the target's receipt (spec §3.3), so the nine fields are
  // there and the state is the one the resume produced.
  assert.equal(receipt.state, "running");
  assert.equal(receipt.name, "target");
  assert.equal(Object.keys(receipt).length, 9);
  assert.equal(target.state, "running");
});

test("the surface hands core the controller's own capability set", async (t) => {
  const dir = await root(t);
  const registry = new ProgramRegistry();
  const control = new ControlSurface(registry);
  const target = registry.begin("target", { capabilities: ["write"] });
  target.park({ reason: "explicit", payload: null });
  const seen = [];
  const surface = surfaceOf(ROLES.CODER, new ProgramState("controller"), dir, {
    control: {
      unpark: (request) => {
        seen.push(request);
        return control.unpark(request);
      },
    },
  });
  control.open("controller");
  control.observe("controller", "target");
  // The controller holds nothing, so a target that requires `write` is denied:
  // an empty default would have let it through.
  assert.throws(
    () => surface.surface().unpark("target", null),
    (error) => error.code === "CAPABILITY_DENIED",
  );
  assert.deepEqual(seen[0].capabilities, [], "the controller's own set, not a default");
});
