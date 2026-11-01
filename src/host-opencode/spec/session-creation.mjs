// Session.create input for a forked worker (§4.1, §7.1).
//
// The host accepts `{ parentID, title, agent, model }` and inherits the
// parent's location, metadata and permissions. The adapter sends exactly those
// four facts, because every other field would be an assumption about the child:
//
// - `parentID` keeps the family (depth, cancellation, completion delivery);
// - `agent` is the worker identity, `coder` or `devops`, never `Manager`;
// - `title` is the worker name, so the host's session list reads like the
//   Manager's own roster;
// - `model` is only set when the fork names one; otherwise the host resolves
//   the agent's default.

import { refusal, REFUSALS } from "./refusals.mjs";

export const createInput = ({ parentID, name, role, model }) => {
  if (typeof parentID !== "string" || parentID.length === 0) {
    throw new TypeError("a fork needs the parent session id");
  }
  if (typeof name !== "string" || name.length === 0) {
    return refusal(REFUSALS.NAME);
  }
  if (role !== "coder" && role !== "devops") {
    return refusal(REFUSALS.ROLE_ARG);
  }
  return {
    parentID,
    title: name,
    agent: role,
    ...(model === undefined || model === null ? {} : { model }),
  };
};

// Exactly one devops may be in flight (§2.3). A second fork naming `devops`
// while one runs is refused, not queued: the Manager decides when to free it.
export const devopsRefusal = (inFlight) =>
  inFlight.size === 0 ? null : refusal(REFUSALS.DEVOPS_BUSY, { names: [...inFlight] });
