// Scope at the host boundary (§6.2).
//
// Three host seams meet here, and each does one thing:
//
//   tool.hook("execute.before")  admit   — refuse the call, change nothing
//   tool.hook("execute.after")   settle  — book the turn, persist records
//   session.hook("context")      project — rebuild the request copy
//
// Core's `ScopeHooks` owns the rules; this module owns only the translation
// between host event shapes and the events those hooks take. The turn id is the
// assistant message id: the host has no smaller model-turn identity, and one
// assistant message is one model reply (gaps.mjs PROMPT_TURN_ID).

import { REFUSALS, refusal } from "./refusals.mjs";

// The tool call's annotation, read from its input object, unmodified (§6.2).
// The adapter never rewrites the field; a call without one is refused by core.
export const annotationOf = (input) => {
  if (input === null || typeof input !== "object") return undefined;
  return input.scope;
};

// The member name Scope books: the tool itself. One program call is one beat.
export const memberOf = (event) => event.tool;

// `execute.before`. Returns null to let the call run, or a refusal receipt.
export const admitCall = (hooks, event) => {
  const result = hooks.admit({
    sessionId: event.sessionID,
    member: memberOf(event),
    annotation: annotationOf(event.input),
  });
  if (result.ok !== false) return null;
  return refusal(REFUSALS.SCOPE, { code: result.code, detail: result.message });
};

// `execute.after`. The settle is bookkeeping, not a result: a refusal here is
// recorded, never turned into a second answer.
export const settleCall = async (hooks, event, { turnId, callId, observed = null } = {}) =>
  hooks.settle({
    sessionId: event.sessionID,
    member: memberOf(event),
    annotation: annotationOf(event.input),
    turnId,
    callId,
    observed,
  });

// `session.hook("context")`. `items` are the translated host messages
// (message-adapter.mjs); the answer is the projected copy. The turn boundary is
// closed once, by the plugin, right after this hook — not here, so the close
// happens exactly once per request.
export const projectRequest = (hooks, { sessionId, items, pinned }) =>
  hooks.project({ sessionId, items, pinned });

// End of a model turn (§6.2). Core answers a refusal for a turn whose only
// annotation was `meToo`, and null once the turn is closed. The plugin calls
// this once per request, after the copy is built.
//
// The answer is awaited: core decides on a settled book, so a close that races
// an in-flight settle queues behind it and answers a promise. Awaiting keeps the
// refusal visible; comparing a promise to null would swallow it.
export const closeTurn = async (hooks, sessionId) => await hooks.closeTurn({ sessionId });
