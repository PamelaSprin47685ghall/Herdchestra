// The role a worker runs as comes from its verboseIntent (根 AGENTS.md §2.1, §4.1).
//
// §4.1 defines `fork(name, verboseIntent)` with two arguments, so the identity
// has no argument of its own; §2.1 requires every subagent to be declared coder
// or devops. The declaration therefore lives in the verboseIntent, and the only
// acceptable answers are "one role, stated" or a refusal.

import test from "node:test";
import assert from "node:assert/strict";

import { WORKER_ROLES, isWorkerRole, roleOfVerboseIntent, forkRequest } from "./role-brief.mjs";

const reasonOf = (fn) => {
  try {
    fn();
    return null;
  } catch (error) {
    return error.details?.reason ?? error.code;
  }
};

test("a field naming one role declares it, in every spelling", () => {
  assert.equal(roleOfVerboseIntent("role: coder. Fix the parser."), "coder");
  assert.equal(roleOfVerboseIntent("Role: DEVOPS — run the suite."), "devops");
  assert.equal(roleOfVerboseIntent("identity: devops"), "devops");
  assert.equal(roleOfVerboseIntent("identity is coder"), "coder");
  assert.equal(roleOfVerboseIntent("as: coder"), "coder");
  assert.equal(roleOfVerboseIntent("as = devops"), "devops");
});

test("a lone role keyword declares it; two keywords without a field do not", () => {
  assert.equal(roleOfVerboseIntent("You are the coder for this task."), "coder");
  assert.equal(roleOfVerboseIntent("The devops runs commands."), "devops");
  assert.equal(roleOfVerboseIntent("A coder writes; a devops runs."), null);
});

test("a field naming two roles is ambiguous, not first-wins", () => {
  assert.equal(roleOfVerboseIntent("role: coder, devops. Do it."), null);
  assert.equal(reasonOf(() => forkRequest("w", "role: coder, devops. Do it.")), "AMBIGUOUS_ROLE");
});

test("a verboseIntent that declares nothing is refused; no default role is invented", () => {
  assert.equal(roleOfVerboseIntent("Fix the parser."), null);
  assert.equal(reasonOf(() => forkRequest("w", "Fix the parser.")), "UNDECLARED_ROLE");
  assert.equal(reasonOf(() => forkRequest("w", "")), "INVALID_VERBOSE_INTENT");
  assert.equal(reasonOf(() => forkRequest("w", "   \n ")), "INVALID_VERBOSE_INTENT");
});

test("what a verboseIntent talks about is not what it declares", () => {
  // A coder verboseIntent must forbid bash and may name the devops that owns
  // commands; neither sentence may turn the worker into a devops.
  assert.equal(
    roleOfVerboseIntent("role: coder. Do not use bash: the devops worker owns commands."),
    "coder",
  );
  assert.equal(roleOfVerboseIntent("role: coder. Never call `devops` or `role: devops`."), "coder");
  assert.equal(roleOfVerboseIntent('role: coder. The verboseIntent says "role: devops" as an example.'), "coder");
  assert.equal(roleOfVerboseIntent("role: coder. See https://example.com/role:devops for context."), "coder");
  assert.equal(roleOfVerboseIntent("role: coder.\n```\nrole: devops\n```\n"), "coder");
});

test("an apostrophe inside a word is not a quoted string", () => {
  // A contraction is ordinary prose: treating its apostrophe as an opening
  // quote swallowed the declaration that followed it.
  assert.equal(roleOfVerboseIntent("The worker's job: role: coder. It's static."), "coder");
  assert.equal(roleOfVerboseIntent("role: devops. Don't guess; it's the runner's job."), "devops");
  assert.equal(forkRequest("w", "The worker's job: role: coder. It's static.").role, "coder");
  // A real quoted declaration is still what the verboseIntent talks ABOUT.
  assert.equal(roleOfVerboseIntent("role: coder. The verboseIntent says 'role: devops' as an example."), "coder");
});

test("the two worker roles are the only accepted identities", () => {
  assert.deepEqual([...WORKER_ROLES], ["coder", "devops"]);
  assert.equal(isWorkerRole("coder"), true);
  assert.equal(isWorkerRole("devops"), true);
  assert.equal(isWorkerRole("manager"), false);
  assert.equal(isWorkerRole("plan"), false);
});

test("a fork request carries the name, the verboseIntent and the resolved role", () => {
  const request = forkRequest("w1", "role: devops. Run the suite.");
  assert.deepEqual(request, { name: "w1", verboseIntent: "role: devops. Run the suite.", role: "devops" });
  assert.equal(Object.isFrozen(request), true);
  assert.equal(reasonOf(() => forkRequest("", "role: coder.")), "INVALID_NAME");
});
