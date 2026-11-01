// Which role a host agent runs as (§7.1, §6).
//
// The host identifies an agent by its id, and the adapter owns four ids:
// `Manager`, `coder`, `devops` and `Plan`. Everything else belongs to the host
// (title, summary, compaction); the adapter must not treat those as programs.
//
// A role is a value, not a flag: `roleOf` returns one of the four names or null,
// and a caller that gets null has no program to run.

import { ROLE_OF_AGENT } from "./exports.mjs";

export const ROLE_NAMES = Object.freeze(["manager", "coder", "devops", "plan"]);

export const roleOf = (agentId) => (typeof agentId === "string" ? (ROLE_OF_AGENT[agentId] ?? null) : null);

export const isProgramAgent = (agentId) => roleOf(agentId) !== null;

// The worker role of a fork request. Only a worker identity may be forked;
// `Manager` is not a worker and Plan is never forked (§4.2, §6). The value comes
// from the brief's explicit declaration (§2.1, §4.1); this function only checks
// it, so a caller cannot smuggle in a third identity.
export const workerRole = (value) => (value === "coder" || value === "devops" ? value : null);

// Which face a role runs. Plan is its own face, not `js-manager`.
export const faceOf = (role) => {
  if (role === "manager") return "js-manager";
  if (role === "plan") return "plan";
  return "js-worker";
};
