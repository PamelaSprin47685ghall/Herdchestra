// The Scope hooks a host adapter calls (spec §6.2-§6.2).
//
// A hook object is the whole interface of this package. The adapter owns the
// host's event shapes; it calls:
//
//   admit(event)      -> admission result   (tool hook, before execution)
//   settle(event)     -> settle result      (tool hook, after execution)
//   closeTurn(event)  -> refusal | null     (end of a model turn)
//   project(event)    -> request copy       (request-level context hook)
//   ask(member, sid)  -> synchronous snapshot (`negotiate`, `askPreviousScope`)
//
// `event` is `{ sessionId, member, annotation, turnId, callId?, observed? }`.
// A refusal is always a value: the host must be able to answer a request it
// cannot repair, and a thrown error inside a request hook loses the request.
// Only a missing or malformed *event* is a programming error and throws.

import { ScopeTable } from "./scope.mjs";
import { SCOPE_CODES, invalidArgument, refuse } from "./errors.mjs";

const requireEvent = (event) => {
  if (event === null || typeof event !== "object") {
    throw invalidArgument("a scope hook needs an event object");
  }
  if (typeof event.sessionId !== "string" || event.sessionId.length === 0) {
    throw invalidArgument("a scope hook needs a sessionId");
  }
  return event;
};

const SNAPSHOT_MEMBERS = Object.freeze({
  negotiate: (scope) => scope.negotiate(),
  askPreviousScope: (scope) => scope.askPreviousScope(),
});

export class ScopeHooks {
  #table;

  constructor(table = new ScopeTable()) {
    this.#table = table;
  }

  get table() {
    return this.#table;
  }

  open(options) {
    return this.#table.create(options);
  }

  coldStart(options) {
    return this.#table.coldStart(options);
  }

  scopeFor(sessionId) {
    const scope = this.#table.get(sessionId);
    if (scope === null) {
      return refuse(SCOPE_CODES.SCOPE_UNINITIALIZED, `no scope for session ${sessionId}`, {
        sessionId,
      });
    }
    return scope;
  }

  // Before execution: presence, kind, member and budget. Writes nothing.
  admit(event) {
    const { sessionId, member, annotation } = requireEvent(event);
    const scope = this.scopeFor(sessionId);
    if (scope.ok === false) return scope;
    const result = scope.admit({ member, annotation });
    if (result.ok === false) return result;
    if (result.exempt) return { ...result, sessionId, notice: scope.notice() };
    return {
      ...result,
      sessionId,
      mustReset: !scope.budgetKnown || scope.retention.spent,
      notice: scope.notice(),
    };
  }

  // After execution: settle and persist. Resolves to a receipt or a refusal.
  async settle(event) {
    const { sessionId, member, annotation, turnId, callId = null, observed = null } =
      requireEvent(event);
    const scope = this.scopeFor(sessionId);
    if (scope.ok === false) return scope;
    const result = await scope.call({ member, annotation, turnId, callId, observed });
    if (result.ok === false) return result;
    return {
      ...result,
      sessionId,
      retention: scope.retention,
      notice: scope.notice(),
    };
  }

  // End of a model turn: a turn that only staged `meToo` is refused here.
  //
  // Always a promise. A settle still in flight is not committed yet, so the
  // judgement waits for the queue to drain; with nothing in flight the promise
  // already holds the answer. A host may therefore `await` it, or attach a
  // `.then` and report the refusal through its own channel.
  //
  // Keep the `await` (or `.then`) at the call site. A caller that compares the
  // promise itself — `refusal === null`, `refusal.ok` — never sees the refusal
  // and silently reports "no refusal"; that is the difference between a host
  // that delivers `SCOPE_METOO_ONLY` and one that loses it.
  closeTurn(event) {
    const { sessionId } = requireEvent(event);
    const scope = this.scopeFor(sessionId);
    // A refusal is a value; the ScopeTable's "no scope" answer is a plain
    // `{ ok: false, code, message }` object, not a refusal instance, and the
    // host reports either one the same way.
    if (scope.ok === false) return Promise.resolve(scope);
    return scope.closeTurn();
  }

  // How many `removeLast` beats the session's book has committed, or 0 when it
  // has none. An adapter asks this to LABEL a copy its projection replaced: a
  // rewrite in place is `removeLast`, a cut is `resetScope`. It never decides
  // whether the copy changed — the bytes do (see `compose/request-boundary.mjs`).
  erasedCount(sessionId) {
    const scope = this.#table.get(sessionId);
    if (scope === null || scope === undefined) return 0;
    return scope.erased.length;
  }

  // The request-level context hook: rebuild the request copy.
  project(event) {
    const { sessionId, items = [], pinned, resetCallId } = requireEvent(event);
    const scope = this.scopeFor(sessionId);
    if (scope.ok === false) return scope;
    const options = {};
    if (pinned !== undefined) options.pinned = pinned;
    if (resetCallId !== undefined && resetCallId !== null) options.resetCallId = resetCallId;
    const messages = scope.project(items, options);
    if (messages.ok === false) return messages;
    return {
      ok: true,
      sessionId,
      messages,
      notice: scope.notice(),
    };
  }

  // The synchronous snapshot. `member` is one of the two exempt members.
  // Answers from the newest committed facts; while a call is still settling the
  // answer is a `SCOPE_SNAPSHOT_BUSY` refusal (the book is about to change), and
  // a name that has no snapshot is `SCOPE_MEMBER_UNKNOWN` (§6.2 exempts exactly
  // two members).
  ask(member, sessionId) {
    const snapshot = SNAPSHOT_MEMBERS[member];
    if (snapshot === undefined) {
      return refuse(SCOPE_CODES.SCOPE_MEMBER_UNKNOWN, `${String(member)} has no scope snapshot`, {
        member,
      });
    }
    const scope = this.scopeFor(sessionId);
    if (scope.ok === false) return scope;
    return snapshot(scope);
  }
}
