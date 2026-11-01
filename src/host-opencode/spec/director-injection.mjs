// The director message of the Manager (§5, §5).
//
// §5 says the director message is not part of `common + manager`. The host has
// one request-level seam that can replace the system prompt,
// `session.hook("context")`; the adapter appends the director segment there,
// once, for the Manager's own session and for no other.
//
// The composition itself lives in the agent definition, so the hook only adds
// the segment. Appending is idempotent: a request that already ends with the
// segment is returned unchanged, which matters because the hook may run again
// after a retry.

import { DIRECTOR_MESSAGE } from "../../compose/prompt/director.mjs";
import { ROLE_OF_AGENT } from "./exports.mjs";

// System parts are `{ type: "text", text }` in the host's request copy. The
// adapter keeps them as parts and appends one part.
export const withDirector = (system, agentId) => {
  if (ROLE_OF_AGENT[agentId] !== "manager") return system;
  const parts = Array.isArray(system) ? system : [];
  if (parts.some((part) => part?.text === DIRECTOR_MESSAGE)) return parts;
  return [...parts, { type: "text", text: DIRECTOR_MESSAGE }];
};

// True when this request already carries the segment, used by tests and by a
// reader who wants to check idempotence without running the hook.
export const hasDirector = (system) =>
  Array.isArray(system) && system.some((part) => part?.text === DIRECTOR_MESSAGE);
