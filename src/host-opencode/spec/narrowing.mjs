// Tool catalog per role (§7.1 "工具面", two-layer separation; §6 Plan).
//
// Layer one is visibility: `session.hook("context")` receives `event.tools`, the
// definitions this request advertises, and the adapter deletes every name the
// role must not see. The host documents that this snapshot alone does not stop
// an executor that was already captured, so it is not the security boundary.
//
// Layer two is refusal: `execute-hooks.mjs` refuses a host tool the Manager or
// Plan calls anyway, and every adapter tool re-checks its role inside `execute`.
// This file owns only layer one.

import { MANAGER_FORBIDDEN_TOOLS, PLAN_FORBIDDEN_TOOLS, TOOL_NAMES, WORKER_HOST_TOOLS } from "./capabilities.mjs";

// What each role keeps. The adapter's own tools are listed by name; a tool the
// adapter registers but the role does not own is removed even if it is present.
// `js-review` is the Manager's evaluation face (§8): it is offered while an
// evaluation is open and removed once the review is accepted.
const KEEP = Object.freeze({
  manager: Object.freeze([TOOL_NAMES.manager, TOOL_NAMES.review, TOOL_NAMES.fork, TOOL_NAMES.join, "question"]),
  plan: Object.freeze([TOOL_NAMES.plan, "question"]),
  coder: Object.freeze([TOOL_NAMES.worker, ...WORKER_HOST_TOOLS]),
  devops: Object.freeze([TOOL_NAMES.worker, ...WORKER_HOST_TOOLS]),
});

export const keptTools = (role) => KEEP[role] ?? Object.freeze([]);

// Delete from `tools` (mutated in place, as the host hook expects) every name
// this role must not see. Returns the deleted names for a diagnostic.
export const narrowTools = (tools, role) => {
  const kept = new Set(keptTools(role));
  const removed = [];
  for (const name of Object.keys(tools)) {
    if (kept.has(name)) continue;
    delete tools[name];
    removed.push(name);
  }
  return removed;
};

// True when the role must never run this tool, whatever the catalog says.
export const refusedForRole = (role, tool) =>
  (role === "manager" && MANAGER_FORBIDDEN_TOOLS.includes(tool)) ||
  (role === "plan" && PLAN_FORBIDDEN_TOOLS.includes(tool));

// The review tool is not part of a role's standing surface: it is added for one
// evaluation and withdrawn when the review is accepted (§8).
export const REVIEW_TOOL_FOR = Object.freeze({ manager: TOOL_NAMES.review });
