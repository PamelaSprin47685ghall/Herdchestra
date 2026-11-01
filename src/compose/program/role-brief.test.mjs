// The role a worker runs as comes from its brief (根 AGENTS.md §2.1, §4.1).
//
// §4.1 defines `fork(name, brief)` with two arguments, so the identity has no
// argument of its own; §2.1 requires every subagent to be declared coder or
// devops. The declaration therefore lives in the brief, and the only acceptable
// answers are "one role, stated" or a refusal.

import test from "node:test";
import assert from "node:assert/strict";

import { WORKER_ROLES, isWorkerRole, roleOfBrief, forkRequest } from "./role-brief.mjs";

const reasonOf = (fn) => {
  try {
    fn();
    return null;
  } catch (error) {
    return error.details?.reason ?? error.code;
  }
};

test("a field naming one role declares it, in every spelling", () => {
  assert.equal(roleOfBrief("role: coder. Fix the parser."), "coder");
  assert.equal(roleOfBrief("Role: DEVOPS — run the suite."), "devops");
  assert.equal(roleOfBrief("identity: devops"), "devops");
  assert.equal(roleOfBrief("identity is coder"), "coder");
  assert.equal(roleOfBrief("as: coder"), "coder");
  assert.equal(roleOfBrief("as = devops"), "devops");
});

test("a lone role keyword declares it; two keywords without a field do not", () => {
  assert.equal(roleOfBrief("You are the coder for this task."), "coder");
  assert.equal(roleOfBrief("The devops runs commands."), "devops");
  assert.equal(roleOfBrief("A coder writes; a devops runs."), null);
});

test("a field naming two roles is ambiguous, not first-wins", () => {
  assert.equal(roleOfBrief("role: coder, devops. Do it."), null);
  assert.equal(reasonOf(() => forkRequest("w", "role: coder, devops. Do it.")), "AMBIGUOUS_ROLE");
});

test("a brief that declares nothing is refused; no default role is invented", () => {
  assert.equal(roleOfBrief("Fix the parser."), null);
  assert.equal(reasonOf(() => forkRequest("w", "Fix the parser.")), "UNDECLARED_ROLE");
  assert.equal(reasonOf(() => forkRequest("w", "")), "INVALID_BRIEF");
  assert.equal(reasonOf(() => forkRequest("w", "   \n ")), "INVALID_BRIEF");
});

test("what a brief talks about is not what it declares", () => {
  // A coder brief must forbid bash and may name the devops that owns commands;
  // neither sentence may turn the worker into a devops.
  assert.equal(
    roleOfBrief("role: coder. Do not use bash: the devops worker owns commands."),
    "coder",
  );
  assert.equal(roleOfBrief("role: coder. Never call `devops` or `role: devops`."), "coder");
  assert.equal(roleOfBrief('role: coder. The brief says "role: devops" as an example.'), "coder");
  assert.equal(roleOfBrief("role: coder. See https://example.com/role:devops for context."), "coder");
  assert.equal(roleOfBrief("role: coder.\n```\nrole: devops\n```\n"), "coder");
});

test("an apostrophe inside a word is not a quoted string", () => {
  // A contraction is ordinary prose: treating its apostrophe as an opening
  // quote swallowed the declaration that followed it.
  assert.equal(roleOfBrief("The worker's job: role: coder. It's static."), "coder");
  assert.equal(roleOfBrief("role: devops. Don't guess; it's the runner's job."), "devops");
  assert.equal(forkRequest("w", "The worker's job: role: coder. It's static.").role, "coder");
  // A real quoted declaration is still what the brief talks ABOUT.
  assert.equal(roleOfBrief("role: coder. The brief says 'role: devops' as an example."), "coder");
});

test("the two worker roles are the only accepted identities", () => {
  assert.deepEqual([...WORKER_ROLES], ["coder", "devops"]);
  assert.equal(isWorkerRole("coder"), true);
  assert.equal(isWorkerRole("devops"), true);
  assert.equal(isWorkerRole("manager"), false);
  assert.equal(isWorkerRole("plan"), false);
});

test("a fork request carries the name, the brief and the resolved role", () => {
  const request = forkRequest("w1", "role: devops. Run the suite.");
  assert.deepEqual(request, { name: "w1", brief: "role: devops. Run the suite.", role: "devops" });
  assert.equal(Object.isFrozen(request), true);
  assert.equal(reasonOf(() => forkRequest("", "role: coder.")), "INVALID_NAME");
});
