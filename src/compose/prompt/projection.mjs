// System prompt projection (根 AGENTS.md §5).
//
// There are exactly two compositions and both are plain concatenation:
//   Manager          = COMMON + MANAGER
//   coder, devops    = COMMON + WORKER
// No role marker, no filter, no rendering, no extra layer. The director message
// is a separate role segment (§5.4 gives its text; `director.mjs` copies it) and
// is not part of either composition.

import { refuse } from "../errors.mjs";
import { COMMON, MANAGER, WORKER } from "./files.mjs";

export const ROLES = Object.freeze({
  MANAGER: "manager",
  CODER: "coder",
  DEVOPS: "devops",
});

// The only two tails. Both are whole files.
const TAILS = Object.freeze({
  [ROLES.MANAGER]: MANAGER,
  [ROLES.CODER]: WORKER,
  [ROLES.DEVOPS]: WORKER,
});

export const systemPromptFor = (role) => {
  // Plan is outside this projection (§5: "Plan 不在此投影之内"). It has a
  // prompt, but that prompt is its own role segment (`prompt/plan.mjs`), not a
  // composition of these files; asking for one here is a wiring error, and a
  // silent empty string would be a worse answer than a refusal.
  if (role === "plan") {
    throw refuse("PLAN_OUTSIDE_PROJECTION", "Plan is not part of the three-file projection", {
      role,
      use: "compose/prompt/plan.mjs",
    });
  }
  const tail = TAILS[role];
  if (tail === undefined) {
    throw refuse("UNKNOWN_ROLE", `unknown role: ${role}`, { role });
  }
  return COMMON + tail;
};

export const isWorkerRole = (role) => role === ROLES.CODER || role === ROLES.DEVOPS;

// §5: Plan is outside the three-file projection.
export const outsideProjection = (role) => role === "plan";
