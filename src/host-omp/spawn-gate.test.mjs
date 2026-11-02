// The parent-side identity gate (root §2.1, §7.3 `before_subagent_spawn`).
//
// What is asserted is the hook's ANSWER, not its shape: a declared child passes
// and may carry a model, an undeclared one is refused by name, and the refusal
// names the rule so a caller can act on it. The last case drives the real entry
// point: `herdchestra(api)` must register the hook on the host's own
// `pi.on("before_subagent_spawn", ...)` channel.

import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import { createSubagentSpawnHandler, declaredRoleOf, spawnDecision } from "./spawn-gate.mjs";
import { ROLE } from "./host-facts.mjs";
import herdchestra from "./index.mjs";

const spawnEvent = (agent, extra = {}) => ({
  type: "before_subagent_spawn",
  agent,
  invocationKind: "task",
  patterns: [],
  ...extra,
});

test("only the two worker definitions are a declaration this adapter honours", () => {
  assert.equal(declaredRoleOf("coder"), ROLE.CODER);
  assert.equal(declaredRoleOf("devops"), ROLE.DEVOPS);
  // The host resolves the definition name; case and surrounding space are not
  // identity, so they are normalised and nothing else is.
  assert.equal(declaredRoleOf(" Coder "), ROLE.CODER);
  assert.equal(declaredRoleOf("DEVOPS"), ROLE.DEVOPS);
  for (const other of ["task", "scout", "reviewer", "seance", "sonic", "herd-manager", "plan", "worker", ""]) {
    assert.equal(declaredRoleOf(other), null, other);
  }
  for (const notAName of [undefined, null, 7, {}, ["coder"]]) {
    assert.equal(declaredRoleOf(notAName), null, String(notAName));
  }
});

test("a declared child passes, and carries a model only when the adapter assigns one", async () => {
  const plain = createSubagentSpawnHandler();
  for (const agent of ["coder", "devops"]) {
    assert.deepEqual(await plain(spawnEvent(agent)), {}, agent);
  }
  // §7.3 says `{ model }` is optional. This adapter assigns no model of its own,
  // so the host keeps its own resolution.
  const withModel = createSubagentSpawnHandler({ modelFor: (role) => (role === ROLE.DEVOPS ? "test/devops-model" : null) });
  assert.deepEqual(await withModel(spawnEvent("devops")), { model: "test/devops-model" });
  assert.deepEqual(await withModel(spawnEvent("coder")), {});
  // A chooser that throws has named no model; the declared child still spawns.
  const throwing = createSubagentSpawnHandler({ modelFor: () => { throw new Error("no model"); } });
  assert.deepEqual(await throwing(spawnEvent("coder")), {});
});

test("an undeclared child is refused by name, with the rule in the reason", async () => {
  const hook = createSubagentSpawnHandler();
  for (const agent of ["task", "scout", "seance", "reviewer", "herd-manager", undefined]) {
    const answer = await hook(spawnEvent(agent));
    assert.equal(answer.block, true, String(agent));
    assert.match(answer.reason, /SPAWN_UNDECLARED_ROLE/);
    assert.match(answer.reason, /Coder or DevOps/);
    // The refusal names what was actually asked, so a reader can tell a
    // mistyped definition from a missing one.
    if (typeof agent === "string") assert.match(answer.reason, new RegExp(`\`${agent}\``));
  }
  // The same answer whether the hook is asked through its decision helper.
  assert.equal(spawnDecision(spawnEvent("scout")).block, true);
});

test("for any spawn event, exactly the two worker definitions pass", async () => {
  await fc.assert(
    fc.asyncProperty(fc.string(), fc.constantFrom("task", "eval"), async (agent, invocationKind) => {
      const hook = createSubagentSpawnHandler();
      const answer = await hook(spawnEvent(agent, { invocationKind }));
      const declared = declaredRoleOf(agent) !== null;
      assert.equal(answer.block === true, !declared);
      if (declared) assert.deepEqual(answer, {});
      else assert.match(answer.reason, /SPAWN_UNDECLARED_ROLE/);
    }),
    { numRuns: 200 },
  );
});

test("the extension registers the hook on the host's own channel", async () => {
  const handlers = new Map();
  const registered = [];
  const api = {
    registerTool: (tool) => registered.push(tool.name),
    getActiveTools: () => [],
    setActiveTools: async () => {},
    on: (event, handler) => {
      if (!handlers.has(event)) handlers.set(event, []);
      handlers.get(event).push(handler);
    },
  };
  herdchestra(api);
  const hooks = handlers.get("before_subagent_spawn") ?? [];
  assert.equal(hooks.length, 1, "exactly one spawn hook is registered");
  // A declared child is answered with the user's TASK model (root §1.1 item 6):
  // the host expands the role alias with `modelRoles.task` before spawning.
  const declared = await hooks[0](spawnEvent("coder"), undefined);
  assert.deepEqual(declared, { model: "@task" });
  assert.deepEqual(await hooks[0](spawnEvent("devops"), undefined), { model: "@task" });
  const refused = await hooks[0](spawnEvent("task"), undefined);
  assert.equal(refused.block, true);
  assert.match(refused.reason, /SPAWN_UNDECLARED_ROLE/);
  // The child is never started for a refusal: the host throws on `block`
  // (`structured-subagent.ts` `applySpawnHook`), so the answer must be the
  // whole verdict.
  assert.equal(registered.includes("js-manager"), true);
});
