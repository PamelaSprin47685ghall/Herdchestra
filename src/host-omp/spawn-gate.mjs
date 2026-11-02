// The parent-side identity gate for a new subagent (root §2.1, §7.3).
//
// Root §2.1: "启动任何子代理时，都必须明确它是 `Coder` 还是 `DevOps`." Root §7.3
// puts the check in the one-shot parent hook `before_subagent_spawn` and names
// the answer: `{ block: true, reason }` for an undeclared child, optionally
// `{ model }` for one that is declared.
//
// ## What the host puts in the event
//
// Measured against ../oh-my-pi at the commit this adapter targets
// (`extensibility/extensions/types.ts:1174-1186`, emitted once per real child
// dispatch by `task/structured-subagent.ts:492-521`):
//
//   type            "before_subagent_spawn"
//   agent           the AGENT DEFINITION name the child runs (`policy.agentName`)
//   invocationKind  "task" | "eval"
//   modelRole?      the role alias the model patterns came from
//   patterns        the model patterns core would spawn with
//   spawnKey?       a stable per-spawn key when the caller supplied one
//
// The child's verbose intent is not in the event: the task text is dispatched after the
// hook resolves the model. So the identity this gate can read is the agent
// definition the spawn names — `coder` or `devops` — and nothing else. An intent
// that says "you are a Coder" while the definition says otherwise is not a
// declaration this hook can see, and inventing a second reading of the task
// text here would be a rule the host never promised to honour.
//
// ## Why an undeclared child is blocked
//
// §2.1 makes the declaration the thing that keeps concurrent work safe: every
// `Coder` is file-only, and command execution is held by exactly one `DevOps`.
// A child that runs under some other definition (`task`, `scout`, `seance`, a
// user-defined agent) has no such boundary, and on this host a subagent is
// headless with `yolo` approval — so the boundary cannot be recovered later.

import { ROLE, WORKER_AGENTS } from "./host-facts.mjs";

// The role a spawn declares, or null when it declares none this adapter runs.
//
// The comparison is on the AGENT DEFINITION name, lower-cased: the host passes
// whatever the spawn policy resolved, and a definition is named in lower case
// (`agents/*.md`). A name outside `WORKER_AGENTS` is not a declaration this
// adapter can honour, whatever the child might do.
export const declaredRoleOf = (agent) => {
  if (typeof agent !== "string") return null;
  const name = agent.trim().toLowerCase();
  if (name.length === 0) return null;
  if (name === "coder") return ROLE.CODER;
  if (name === "devops") return ROLE.DEVOPS;
  return null;
};

// The refusal the host shows the caller. It names what was asked, what this
// adapter requires, and the one thing the caller can do about it — a refusal
// that only says "blocked" leaves the caller guessing at the rule.
export const spawnRefusal = (agent) => ({
  block: true,
  reason:
    `SPAWN_UNDECLARED_ROLE: the subagent was spawned as \`${typeof agent === "string" && agent.length > 0 ? agent : "(unnamed)"}\`, ` +
    `but every child must declare Coder or DevOps (root §2.1). Spawn it with \`agent: "coder"\` ` +
    `or \`agent: "devops"\`, or use \`fork(name, verboseIntent)\`, which declares the role in the verboseIntent.`,
});

// The hook's answer for one spawn event.
//
// `modelFor(role)` is the model this adapter assigns to a declared role, or
// null when it assigns none. `index.mjs` answers `@task` for both worker roles
// (root §1.1 item 6): the host expands the role alias with the user's
// `modelRoles.task` before it spawns (`task/structured-subagent.ts`
// `resolveConfiguredModelPatterns`), and the host's own task agent declares the
// same selector (`task/agents.ts:56`). A host that cannot resolve it keeps its
// own spawn policy; `{ model }` is OPTIONAL, and the adapter never invents a
// concrete model to look authoritative.
export const spawnDecision = (event, { modelFor = null } = {}) => {
  const role = declaredRoleOf(event?.agent);
  if (role === null) return spawnRefusal(event?.agent);
  if (typeof modelFor !== "function") return {};
  let model = null;
  try {
    model = modelFor(role, event);
  } catch {
    // A model chooser that throws has not named a model; refusing the spawn for
    // that would block a child whose identity is fine. The declared child runs
    // on the host's own resolution.
    return {};
  }
  return typeof model === "string" && model.length > 0 ? { model } : {};
};

// The hook a `pi.on("before_subagent_spawn", ...)` registration receives.
// `allowed(agent)` is the one host fact that can widen the set (a user-defined
// definition the operator declared as a worker); it defaults to the two
// Herdchestra definitions and nothing else.
export const createSubagentSpawnHandler = ({ modelFor = null, allowed = null } = {}) => {
  const permitted = typeof allowed === "function" ? allowed : (agent) => declaredRoleOf(agent) !== null;
  return async (event) => {
    if (event === null || typeof event !== "object") return undefined;
    const role = declaredRoleOf(event.agent);
    if (role === null || permitted(event.agent) !== true) return spawnRefusal(event.agent);
    if (typeof modelFor !== "function") return {};
    return spawnDecision(event, { modelFor });
  };
};

export { WORKER_AGENTS };
