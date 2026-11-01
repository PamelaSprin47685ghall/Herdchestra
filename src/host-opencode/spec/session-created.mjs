// What the adapter does when a session appears (§6.1 step 1, §6.2).
//
// A session binds its language once, at creation, and opens a Scope. A forked
// child copies the starter's language; it does not re-run the ladder (§6.1).
//
// The host reports creation as the durable event `session.created`, which the
// client's event stream carries (`ctx.event.subscribe`). The adapter reacts to
// that event rather than to the prompt: the prompt arrives after the session
// already exists, and the language must be bound before the first request.

import { bindSession } from "../../core/pair-hint/language.mjs";

// `stored` is the binding the starter already has, or null for a top-level
// session. `ladder` is `{ explicit, host, locale }` for a top-level session.
export const bindForSession = ({ sessionId, stored = null, ladder = {} }) => {
  if (stored !== null) return bindSession({ sessionId, forkOf: stored });
  return bindSession({ sessionId, ladder });
};

// The language a child inherits: the parent's stored binding, looked up by the
// parent's session id. A parent that never bound (created before the plugin
// loaded) falls back to the ladder, and that fallback is recorded as such by
// `resolveLanguage`'s `source`.
export const bindingForChild = ({ sessionId, parentID, bindings, ladder = {} }) => {
  const parent = parentID === undefined ? null : (bindings.get(parentID) ?? null);
  return bindForSession({ sessionId, stored: parent, ladder });
};
