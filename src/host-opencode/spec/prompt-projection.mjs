// System prompt per agent (§5, §5, §5) and the director message (§5).
//
// The text is not written here. compose owns the three continuous strings and
// the two compositions; this module only answers "which composition for which
// host agent". That keeps one原文 in the repository (§5: one sentence, once).
//
// The director message is not part of either composition (§5). The host has
// one system prompt per agent (`Agent.Info.system`) and one request-level hook
// (`session.hook("context")`) that may replace it; the adapter puts the
// composition in the agent definition and keeps the director message as the
// role segment compose hands to the Manager surface.

import { systemPromptFor } from "../../compose/prompt/projection.mjs";
import { DIRECTOR_MESSAGE } from "../../compose/prompt/director.mjs";
import { ROLE_OF_AGENT } from "./exports.mjs";

// The system prompt of one host agent, or null for an agent we do not own.
export const agentSystemPrompt = (agentId) => {
  const role = ROLE_OF_AGENT[agentId];
  return role === undefined ? null : systemPromptFor(role);
};

// The director segment of the Manager, and only of the Manager.
export const directorSegment = (agentId) => (ROLE_OF_AGENT[agentId] === "manager" ? DIRECTOR_MESSAGE : null);
