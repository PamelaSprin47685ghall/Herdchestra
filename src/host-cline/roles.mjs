// The roles this adapter names (根 AGENTS.md §2.1, §6).
//
// `compose` owns the role values (`compose/program/surface.mjs` `ROLES`); this
// module only renames them so the host files read the same way. Nothing here is
// a second source of truth.

import { ROLES } from "../compose/program/surface.mjs";

export { ROLES };

// The tools that run one of our program faces (spec §3, §8).
export const FACE_TOOLS = Object.freeze(new Set(["js-manager", "js-worker", "js-review", "js-plan"]));

// Plan's tools: it reads and writes one draft and nothing else (根 §2.2). Every
// other host tool is refused for a Plan session, the same way the Manager's face
// is refused — the visible set is not an execution grant (根 §7.2).
export const PLAN_FORBIDDEN = Object.freeze([
  "read_files",
  "search_codebase",
  "run_commands",
  "fetch_web_content",
  "apply_patch",
  "editor",
  "skills",
  "spawn_agent",
  "team_run_task",
  "team_await_runs",
  "js-worker",
  "fork",
  "join",
]);

// Tools a Manager may never call (spec §2.1, §2.4, §8): it does not read, run,
// grep, build, or spawn the host's own subagents. Names are the host's builtin
// tool names as `runtime-builder` registers them.
export const MANAGER_FORBIDDEN = Object.freeze([
  "read_files",
  "search_codebase",
  "run_commands",
  "fetch_web_content",
  "apply_patch",
  "editor",
  "skills",
  "spawn_agent",
  "team_run_task",
  "team_await_runs",
]);

// Which face a role may own. A worker never gets `js-manager`. `fork`/`join`
// are the dispatch primitives of §4.1 and belong to every role that may
// dispatch — the Manager and every worker; Plan never has them (§2.2, §6).
//
// `js-review` is the Manager's evaluation face (根 §2.3) and is not a
// permanent member of the role's catalog: the session's evaluation window
// decides. A caller that omits `window` asks the static question only (who may
// ever own the face). A worker never passes the role test, whatever the window
// says.
export const surfaceAllowed = (role, name, { window = null } = {}) => {
  if (name === "js-manager") return role === ROLES.MANAGER;
  if (name === "js-review") {
    if (role !== ROLES.MANAGER) return false;
    return window === null || window.open === true;
  }
  if (name === "js-plan") return role === ROLES.PLAN;
  if (name === "js-worker") return role !== ROLES.MANAGER && role !== ROLES.PLAN;
  if (name === "fork" || name === "join") return role !== ROLES.PLAN;
  return true;
};

// The role a tool call comes from. A top-level call in a session whose role is
// Plan is Plan's, even though a top-level caller is otherwise the Manager: the
// session's own role is the fact, and it is what the entry and the ledger were
// built for (根 §2.2). A child call keeps the worker reading, because a worker of a
// Plan session would still be a worker.
export const callRole = ({ session, roleFor, context }) => {
  const role = roleFor(context);
  const snapshot = context?.snapshot;
  const topLevel =
    snapshot === null || typeof snapshot !== "object" || snapshot.parentAgentId === null || snapshot.parentAgentId === undefined;
  return topLevel && session.declaredRole === ROLES.PLAN ? ROLES.PLAN : role;
};
