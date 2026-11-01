// Which adapter tool a role owns (§7.1 tool list, §6, §8).
//
// `js-manager` belongs to the Manager, `plan` to Plan, `js-worker` to both
// worker identities, `js-review` to the Manager alone and only while it
// evaluates. `fork` and `join` are the dispatch primitives of §4.1: every role
// that may dispatch holds them — the Manager and every worker — while Plan
// never does (§2.2, §6). A worker's fork fills its own roster (§4.1), which is
// not the same thing as fission (§4.2).
//
// A tool call whose agent does not own it is refused here, before any program
// runs. This is the second layer of §7.1, not the catalog.

import { TOOL_NAMES } from "./capabilities.mjs";

const OWNER = Object.freeze({
  [TOOL_NAMES.manager]: Object.freeze(["manager"]),
  [TOOL_NAMES.worker]: Object.freeze(["coder", "devops"]),
  [TOOL_NAMES.review]: Object.freeze(["manager"]),
  [TOOL_NAMES.plan]: Object.freeze(["plan"]),
  [TOOL_NAMES.fork]: Object.freeze(["manager", "coder", "devops"]),
  [TOOL_NAMES.join]: Object.freeze(["manager", "coder", "devops"]),
});

export const ownerRoles = (tool) => OWNER[tool] ?? Object.freeze([]);

export const ownsTool = (role, tool) => ownerRoles(tool).includes(role);
