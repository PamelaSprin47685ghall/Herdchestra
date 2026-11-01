// Session control from a tool (spec §4.2 replace, 根 §2.3 relay).
//
// OMP gives a TOOL no session control: the context a tool receives exposes
// `sessionManager` read-only, and `newSession`/`branch` live on
// `ExtensionCommandContext` only (extensions/types.ts:915). That is a fact about
// the interface, not about the session object: `AgentSession` itself carries
// `newSession`, `branch`, `waitForIdle`, `isSessionTransitioning` and
// `sessionFile`, and its own command-context wrapper
// (`agent-session.ts:9093` `#createCommandContext`) is nothing more than those
// methods forwarded.
//
// So this module does exactly what that wrapper does, on a session this adapter
// holds. Two rules make it honest:
//
//   - `settled()` reports whether the session can be handed control at this
//     instant. A session that is streaming, transitioning, or holding queued
//     work is NOT settled, and the caller refuses instead of forcing it.
//   - a call the session does not offer returns `{ ok: false, reason }`. The
//     adapter never assumes a transition happened because a promise resolved.
//
// What this module offers, and what the adapter actually performs:
//
//   silence()          PERFORMED. Stop the turns this session is producing
//                      without ending it (fission's present replacement): abort
//                      the live turn and drop the queued work. The session
//                      object stays alive, so its transcript and delivered view
//                      stay readable.
//   waitSettled()      PERFORMED. Wait, under the request's own deadline, for
//                      the session to be between turns.
//   waitOutsideOwnTurn() PERFORMED. Wait for THIS event's in-flight bracket to
//                      end, which is what the deferred relay needs before it
//                      may switch the transcript (see `ownTurnCleared`).
//   reason()           PERFORMED. Why control is unavailable right now.
//   newConversation()  NOT a relay success path. It switches THIS session's
//                      transcript, but the relay's successor is a different
//                      session object, so switching one without committing the
//                      other is half a tenure (root 根 §2.4 第 3 步). It stays as
//                      the primitive a host with an atomic record would use.

// The object that can perform a session transition.
//
// The relay needs `newSession` + `getSessionFile`; the incumbent transcript is
// replaced by the host's own in-place reset. On the REAL host those live on
// `ctx.sessionManager` (`SessionManager`, `agent-session.ts:903` exposes the
// live instance), not on `ctx` — `ExtensionContext.sessionManager` is typed as
// `ReadonlySessionManager`, but the runner passes the live `SessionManager`
// (`runner.ts` `sessionManager: this.sessionManager`), so `newSession` is
// present at runtime. A surface built from an `AgentSession` (a lane, or a
// test) exposes `newSession` on itself.
//
// Reading this wrong is what refused every real relay with `NO_NEW_SESSION`:
// the code asked the ctx for `newSession`, and the ctx has never had it.
export const switchTarget = (session) => {
  if (session === null || typeof session !== "object") return null;
  if (typeof session.newSession === "function") return session;
  const manager = session.sessionManager;
  if (manager !== null && typeof manager === "object" && typeof manager.newSession === "function") return manager;
  return null;
};

// The live session object behind a ctx, for the members `ExtensionContext` does
// not carry (`isStreaming`, `waitForIdle`, `isSessionTransitioning`).
//
// On the REAL host the extension context is a plain object with no back-link to
// its `AgentSession`: `runner.ts` `createContext` builds one object per emit and
// exposes `sessionManager`, `agent`, `isIdle`, `abort` — never the session. So
// no live object is reachable from it, and reading the ctx's own members as if
// it were a session is what broke the deferred relay: `isIdle()` answers from
// `session.isStreaming`, and the relay's own `agent_end` handler runs inside the
// prompt's in-flight bracket, so every commit attempt read `SESSION_STREAMING`
// and was deferred again, forever, while the host reported nothing.
//
// A session created through the SDK IS its own context (a lane), and some host
// shapes carry the session as `.session`, so both are still read.
export const liveSession = (session) => {
  const inner = session?.session;
  return inner !== null && typeof inner === "object" ? inner : session;
};

// Whether a context can answer liveness from a live session object of its own.
// A ctx that only offers `isIdle()` answers about the event's OWN in-flight
// bracket (see `ownTurnCleared`), not about the state after that bracket.
export const readsLiveSession = (session) => {
  if (session === null || typeof session !== "object") return false;
  const live = liveSession(session);
  return typeof live?.isStreaming === "boolean";
};

// The deferred relay runs from `agent_end`, and OMP emits that event INSIDE
// `#endInFlight`, before `#promptInFlightCount` drops. A handler's own
// `isIdle()` therefore answers "streaming" for the turn that has just ended, and
// the host's `waitForIdle()` would deadlock against the very handler awaiting
// it (and be cut off at the 30s handler timeout anyway).
//
// So the adapter separates the two facts the ctx's single `isIdle()` merges:
//   - `ownTurnCleared` is this event's own bracket; it clears when the handler
//     returns, so a deferred step WAITS for it (`waitOutsideOwnTurn`);
//   - `reason()` reports whether control is available RIGHT NOW, so a ctx
//     without a live session object is busy while its own bracket is open.
// A surface with a live session object (a lane, a worker) exposes `isStreaming`
// directly, where the two are the same fact; nothing is invented there.
export const ownTurnCleared = (session) => {
  if (readsLiveSession(session)) return true;
  return busyReason(session) === null;
};

// Why a session cannot take control right now, or null when it can.
//
// Two liveness shapes exist and both must be read:
//
//   - `isStreaming` on the session (an `AgentSession`, a lane): true while a
//     model turn streams OR while a prompt is in flight (`agent-session.ts:6825`),
//     so an admitted-but-unstarted turn also counts as busy — exactly the case a
//     fission owner is in when it calls `fission` from inside its own turn.
//   - `isIdle()` on the `session_start` ctx (`ExtensionContext`): the host's own
//     answer, and the ONLY liveness fact that ctx offers. Its `isStreaming` is
//     `undefined` (measured on 18.14.1/18.14.2), so a check that reads only the
//     session members would report a streaming ctx as settled and switch the
//     transcript out from under a live turn.
//
// A surface that can answer neither is not assumed settled: it reports
// `NO_SESSION_LIVENESS`, because "cannot tell" and "is idle" are different
// facts and only one of them permits a transition.
export const busyReason = (session) => {
  if (session === null || typeof session !== "object") return "NO_SESSION";
  if (switchTarget(session) === null) return "NO_NEW_SESSION";
  const live = liveSession(session);
  if (live.isSessionTransitioning === true) return "SESSION_TRANSITIONING";
  if (live.isStreaming === true) return "SESSION_STREAMING";
  const queued = live.queuedMessageCount;
  if (typeof queued === "number" && queued > 0) return "SESSION_QUEUED_WORK";
  if (typeof live.isStreaming === "boolean") return null;
  // No `isStreaming` here: ask the ctx. `isIdle()` is synchronous on the host.
  if (typeof session.isIdle === "function") {
    return session.isIdle() === true ? null : "SESSION_STREAMING";
  }
  return "NO_SESSION_LIVENESS";
};

// Whether the session may be handed control at this instant.
export const settled = (session) => busyReason(session) === null;

// The key the replacement mark is carried under on the SESSION object. See
// `SessionControl.replace`: the mark belongs to the session, because the control
// wrapper is rebuilt on every event and a mark kept only in one wrapper would be
// forgotten at the next emit.
const REPLACED_KEY = Symbol.for("herdchestra.presentReplaced");

// The mark a session carries, or null. Read from the live session object (a
// lane, a worker) as well as from the wrapper's own context, so a wrapper built
// after the mark still sees it.
const carriedMark = (session) => {
  if (session === null || typeof session !== "object") return null;
  const live = liveSession(session);
  const own = session[REPLACED_KEY] ?? null;
  if (own !== null) return own;
  return live !== session && live !== null && typeof live === "object" ? live[REPLACED_KEY] ?? null : null;
};

// One control surface over one session. Both transitions answer a receipt that
// names what really happened; a transition the session did not perform is
// `ok: false` with a reason, never a success.
export class SessionControl {
  #session;
  #state;
  #replaced;
  #turnEnd;

  // `state` is the owning `SessionState`. The relay record needs the tenure and
  // milestone the ledger will hold AFTER the successor opens, and only the
  // state knows them; a control surface built without one cannot describe a
  // record, and the relay port refuses rather than guess (ports.mjs).
  constructor(session = null, { state = null } = {}) {
    this.#session = session ?? null;
    this.#state = state ?? null;
    // Fission's mark (§4.2 step 4): the present this session shows has been
    // replaced by a lane group. It is a fact about the session, not about the
    // turn that set it, so it survives the turn and every turn after it.
    this.#replaced = null;
    // What to do at the end of the turn in flight. One list, drained once.
    this.#turnEnd = [];
  }

  get session() {
    return this.#session;
  }

  // Adopt the context of the event now being handled. The host builds a fresh
  // `ExtensionContext` per emit, and the adapter refreshes its session on every
  // event. The control surface is a long-lived object (the fission port holds
  // it), so it is REBOUND in place rather than replaced: a new wrapper would
  // leave every holder reading the `session_start` context forever.
  attach(session = null) {
    if (session !== null && session !== undefined) this.#session = session;
    return this;
  }

  // Whether this session's present has been replaced, and by what. `null` when
  // it has not.
  get replaced() {
    return this.#replaced ?? carriedMark(this.#session);
  }

  // Mark the present as replaced (§4.2 step 4). From this instant the session
  // produces no new turn: `busyReason` reports `SESSION_REPLACED`, which is what
  // the request path and the tool gate refuse on.
  //
  // Marking is idempotent: a second fission on the same owner is refused by the
  // group layer (`AlreadyActive`), and a second mark would only restate the same
  // fact.
  replace({ groupId = null, lanes = null, at = Date.now() } = {}) {
    if (this.#session === null || typeof this.#session !== "object") {
      return { ok: false, reason: "NO_SESSION" };
    }
    if (this.#replaced === null) {
      this.#replaced = Object.freeze({ groupId, lanes, at });
      // The mark is a fact about the SESSION, not about this wrapper. The
      // adapter rebuilds the wrapper on every `bind`/`refreshContext` (the host
      // builds a fresh ctx per emit), so a mark held only here was lost on the
      // next event and the session went on producing turns. The session object
      // outlives every wrapper, so the fact is written there once and read back
      // by whichever wrapper is current.
      try {
        Object.defineProperty(this.#session, REPLACED_KEY, {
          value: this.#replaced,
          enumerable: false,
          configurable: true,
          writable: true,
        });
      } catch {
        // A frozen session cannot carry the mark; the wrapper's own copy still
        // governs every decision this instance makes, and `reason()` reports it.
      }
    }
    return { ok: true, replaced: this.#replaced };
  }

  // The group converged: the owner is the single present again (§4.2 step 10).
  // The mark was only ever a fact about the time the lanes ran, and leaving it
  // set made the owner refuse every later turn with `SESSION_REPLACED` — a
  // session that can never answer its own merged result. The mark lives on the
  // session object (see `replace`), so it is removed there too.
  restore() {
    if (this.#session === null || typeof this.#session !== "object") {
      return { ok: false, reason: "NO_SESSION", restored: false };
    }
    const wasReplaced = this.replaced !== null;
    this.#replaced = null;
    try {
      delete this.#session[REPLACED_KEY];
    } catch {
      // A frozen session carries no writable mark; nothing else to clear.
    }
    const live = liveSession(this.#session);
    if (live !== this.#session && live !== null && typeof live === "object") {
      try {
        delete live[REPLACED_KEY];
      } catch {}
    }
    return { ok: true, restored: wasReplaced };
  }

  // Run `fn` once when the turn in flight ends, or at once when no turn is in
  // flight. The callback is how fission interrupts a turn that was admitted
  // before its present was marked: aborting now would race the host's own turn
  // bookkeeping, and the tail of the turn is where the queued brief would start.
  //
  // A callback that throws is dropped, never rethrown into the host's loop: it
  // runs from a turn's end, and a throw there would fail the turn after it.
  atTurnEnd(fn) {
    if (typeof fn !== "function") return { ok: false, reason: "NO_CALLBACK" };
    if (this.#session === null || typeof this.#session !== "object") return { ok: false, reason: "NO_SESSION" };
    if (this.reason() === null) {
      try {
        fn();
      } catch {}
      return { ok: true, ran: true };
    }
    this.#turnEnd.push(fn);
    return { ok: true, ran: false, pending: this.#turnEnd.length };
  }

  // The turn ended: run what was waiting for it. Called by the surface at the
  // host's own turn boundary (`agent_end`), never from inside a tool call.
  drainTurnEnd() {
    const waiting = this.#turnEnd;
    this.#turnEnd = [];
    for (const fn of waiting) {
      try {
        fn();
      } catch {}
    }
    return waiting.length;
  }

  // The payload the relay commit writes. The tenure is the one the successor
  // WILL have (this tenure + 1), because the record describes the successor
  // that starts when it is published; `SessionState.handOver` then opens exactly
  // that tenure in the ledger.
  //
  // `retiredTranscript` is passed straight through: the port writes the
  // pre-switch record into the file it is about to retire, and the re-commit
  // after a real reset writes the successor's copy (`ports.mjs`).
  relayPayload({ fenceThrough = 0, successorSessionId = null, retiredTranscript = true } = {}) {
    if (this.#state === null) return null;
    const tenure = this.#state.ledger.tenure + 1;
    return this.#state.relayPayloadFor({
      // The successor is this session continued, so the name it is recorded
      // under is this session's own. The host's live id is read first: the
      // state's copy is fixed at bind time, and the host is the authority.
      successorSessionId: successorSessionId ?? this.sessionId() ?? `${this.#state.sessionId}#tenure-${tenure}`,
      previousSessionFile: this.sessionFile(),
      fenceThrough,
      retiredTranscript,
    });
  }

  // Why control is unavailable now, or null.
  reason() {
    // A replaced present is never handed control again (§4.2 step 4). The fact
    // outranks the liveness one: an idle replaced session is still replaced,
    // and reading it as settled is what would let it start the next turn.
    if (this.replaced !== null) return "SESSION_REPLACED";
    return busyReason(this.#session);
  }

  // A successor conversation: a new session record with an empty transcript.
  //
  // This IS the relay's second half (根 §2.4 第 3 步). The record is committed
  // first (`ports.mjs`), and only then does the incumbent give up its
  // transcript; the successor is the SAME session continued under a new tenure,
  // so the participant's identity, ledger, role and DevOps binding carry over.
  //
  // The transition runs on the object that has `newSession`. On the real host
  // that is `ctx.sessionManager`, not `ctx` (`switchTarget`); on a lane it is
  // the session itself. `newSession` writes the successor header durably before
  // it returns (`SessionManager.newSession` → `ensureOnDisk`), so a resolved
  // call means the successor's transcript exists.
  //
  // `AgentSession.newSession` returns false when a `session_before_switch`
  // handler cancelled the switch (`agent-session.ts:11341`), and that false is
  // reported as a refusal — a cancelled switch leaves the incumbent in place.
  // `SessionManager.newSession` instead returns the new session file, so both
  // shapes are read.
  async newConversation() {
    const reason = this.reason();
    if (reason !== null) return { ok: false, reason };
    const target = switchTarget(this.#session);
    const previousSessionFile = this.sessionFile();
    let result;
    try {
      // `parentSession` records the lineage in the successor's own header.
      // `SessionManager.#resetToNewSession` writes it VERBATIM
      // (`parentSession: options?.parentSession`, session-manager.ts:2045), so
      // its meaning is whatever the caller puts there — and the host's own
      // callers disagree:
      //
      //   - `branchFrom` / `fork` write a session ID (`previousSessionId`);
      //   - `SessionManager.resume` writes a session FILE PATH
      //     (`parentSession: this.#persist ? sourceSessionFile : undefined`),
      //     and `SessionManager.open` documents its option as "Parent session
      //     file" (session-manager.ts:4364).
      //
      // A relay is a transcript REPLACEMENT, which is the resume shape: the
      // successor must point at the transcript it replaced, so a reader can
      // open it and tell a relay from an unrelated new session. Passing the id
      // here was the live defect — the id is not a path, the header's field is
      // consumed as a path, and a later resume of the successor would look for
      // a file named after an id and find nothing. Measured live: with no
      // argument at all the successor header carried `parentSession: null`.
      result = await target.newSession(previousSessionFile === null ? {} : { parentSession: previousSessionFile });
    } catch (error) {
      return { ok: false, reason: "NEW_SESSION_FAILED", detail: String(error?.message ?? error) };
    }
    if (result === false || (result !== null && typeof result === "object" && result.cancelled === true)) {
      return { ok: false, reason: "SESSION_SWITCH_CANCELLED", previousSessionFile };
    }
    const sessionFile = this.sessionFile();
    return {
      ok: true,
      sessionFile,
      previousSessionFile,
      // The transcript really changed. A manager that returns a path but does
      // not move the session (or a session whose file is not exposed) reports
      // `replaced: false` rather than claiming a reset that did not happen.
      replaced: sessionFile !== null && sessionFile !== previousSessionFile,
    };
  }

  // The id of the session this surface serves, or null. It is the lineage value
  // a successor's header carries, and it survives the transcript switch (the
  // manager mints a NEW id for the successor, so this is read BEFORE the call).
  sessionId() {
    // Read through `switchTarget`, the one helper that knows both shapes: on the
    // real host the id lives on `ctx.sessionManager` (the ctx itself has no
    // `getSessionId`), and on a lane it is the session's own. Asking the session
    // directly returned null on every real run, which silently fell back to the
    // `<id>#tenure-<n>` form.
    const target = switchTarget(this.#session);
    if (target !== null && typeof target.getSessionId === "function") {
      const id = target.getSessionId();
      if (typeof id === "string" && id.length > 0) return id;
    }
    const direct = liveSession(this.#session)?.getSessionId?.();
    return typeof direct === "string" && direct.length > 0 ? direct : null;
  }

  // The transcript this session writes to, read from whichever object owns it.
  // `SessionManager.getSessionFile()` is the host's accessor; a session object
  // (a lane, a test) carries `sessionFile` directly.
  sessionFile() {
    const target = switchTarget(this.#session);
    if (target !== null && typeof target.getSessionFile === "function") {
      const file = target.getSessionFile();
      return typeof file === "string" ? file : null;
    }
    const live = liveSession(this.#session);
    const direct = live?.sessionFile ?? this.#session?.sessionFile;
    return typeof direct === "string" ? direct : null;
  }

  // Stop the turns this session is producing, without ending the session.
  //
  // `abort` is the host's own interrupt (`agent-session.ts:11300`): it stops the
  // live turn and requeues what it took out of the queues as persisted entries.
  // The queued work that was never in flight is then cleared, so the session
  // stops producing turns of its own. The session object is not disposed: its
  // transcript and its delivered view stay readable, which is what a fission
  // owner needs after its present is replaced.
  async silence() {
    const session = this.#session;
    if (session === null || typeof session !== "object") return { ok: false, reason: "NO_SESSION" };
    // WHICH `abort` this is decides what the call does, and the two are not
    // interchangeable:
    //
    //   ctx.abort()        `ExtensionContext.abort` (`extensions/types.ts`): it
    //                      stops the turn THIS EVENT belongs to. Called from
    //                      inside a tool call, that is exactly the turn in
    //                      flight — the one that called `fission` — and it is
    //                      the only interrupt a ctx offers.
    //   session.abort()    `AgentSession.abort` (`agent-session.ts:11215`): the
    //                      host's own interrupt. It aborts the live run, drains
    //                      the queue (`clearQueue({ forInterrupt: true })`) and
    //                      then `await agent.waitForIdle()`.
    //
    // A session object (a lane, a worker started through the SDK) is its own
    // context, so it has only the first. Reading `abort` off the ctx as if it
    // were the session's was wrong twice: on the real host the ctx's `abort`
    // carries no session argument, and on a session object the call stopped the
    // calling turn and nothing else — measured by this test, which saw the abort
    // recorded while the session kept streaming.
    //
    // So the turn this event belongs to is stopped through the ctx, and the
    // session's own turn is stopped through the session when it has an `abort`
    // of its own. When neither can run, the call says so by name.
    const live = liveSession(session);
    const sessionAbort = live !== session && typeof live?.abort === "function" ? live.abort : null;
    const contextAbort = typeof session.abort === "function" ? session.abort : null;
    if (sessionAbort === null && contextAbort === null) return { ok: false, reason: "NO_ABORT" };
    let aborted = false;
    try {
      if (sessionAbort !== null) await sessionAbort.call(live, { reason: "Replaced by a fission lane group" });
      else await contextAbort.call(session, { reason: "Replaced by a fission lane group" });
      aborted = true;
    } catch (error) {
      return { ok: false, reason: "ABORT_FAILED", detail: String(error?.message ?? error) };
    }
    const stopped = await this.#settleTurns();
    return {
      ok: aborted && stopped.ok,
      reason: stopped.reason,
      aborted,
      cleared: stopped.cleared,
      stillStreaming: stopped.stillStreaming,
    };
  }

  // Drop the queued work and read the session's own liveness back. Split out of
  // `silence` so the port can settle a replaced present without aborting the
  // turn it is already running in (see `stop`).
  async #settleTurns() {
    const live = liveSession(this.#session);
    // Queued work is dropped only when the session exposes its queue. A session
    // without it reports that fact rather than claiming an empty queue.
    let cleared = null;
    if (typeof live?.clearQueue === "function") {
      try {
        live.clearQueue();
        cleared = true;
      } catch {
        cleared = false;
      }
    }
    // The turn is stopped and no new one is queued. `isStreaming` is re-read
    // rather than assumed: an abort that did not land leaves it true.
    const stillStreaming = live?.isStreaming === true;
    return { ok: !stillStreaming, reason: stillStreaming ? "STILL_STREAMING" : null, cleared, stillStreaming };
  }

  // Stop the session's own turns without aborting the turn THIS event belongs
  // to (spec §4.2 step 4, the in-turn case).
  //
  // `fission` runs from a tool call of the owner's own turn. The host offers no
  // way to interrupt the run that is executing the very tool call it is in:
  // `AgentSession.abort` ends with `await agent.waitForIdle()` (agent-session.ts:11215),
  // which cannot settle while this call is on the stack, so awaiting it would
  // hang the call that asked for the replacement. What CAN be done from here,
  // and is done here, is the part that actually stops the old present producing
  // new turns: drop every queued turn and let the mark refuse every later one.
  // The turn in flight then ends when its tool results are in, and the host's
  // own interrupt is armed for that boundary by the port (`atTurnEnd`).
  async stop() {
    if (this.#session === null || typeof this.#session !== "object") return { ok: false, reason: "NO_SESSION" };
    return this.#settleTurns();
  }

  // Wait until the session is settled, or report what is still busy.
  //
  // The wait is bounded by the REQUEST's own deadline (spec §3.3: a tool call's
  // deadline is fixed once at admission), never by a timer this adapter
  // invents: an invented budget would turn a slow host into a failure at an
  // undocumented threshold. `deadline` is a core `Deadline` (it answers
  // `remainingMs()`); without one the wait is a single check, because this
  // adapter has no clock of its own to bound a longer wait with.
  //
  // `waitForIdle` is the host's own wait (`agent-session.ts:9050`). It settles
  // the streaming turn, not queued work, so the loop re-reads `reason()` every
  // pass and stops the moment the session is genuinely free.
  async waitSettled({ deadline = null } = {}) {
    const session = this.#session;
    if (session === null || typeof session !== "object") return { ok: false, reason: "NO_SESSION" };
    for (;;) {
      const reason = this.reason();
      if (reason === null) return { ok: true };
      // A session that cannot transition at all will not start doing so.
      if (reason === "NO_NEW_SESSION") return { ok: false, reason };
      const remaining = deadline === null ? null : deadline.remainingMs();
      if (remaining === null || remaining <= 0) {
        return { ok: false, reason: "WAIT_EXPIRED", busy: reason };
      }
      // The host's own wait exists only on a live session object. A ctx has
      // none, and its `isIdle()` cannot change while this turn is in flight,
      // so there is nothing to await there: the request's deadline decides.
      const live = liveSession(session);
      const wait = typeof live?.waitForIdle === "function" ? live.waitForIdle : null;
      if (wait !== null) {
        // The host's promise may never settle (a turn parked on a dialog), so
        // the request's own deadline races it.
        await Promise.race([
          Promise.resolve(wait.call(live)).catch(() => {}),
          new Promise((resolve) => setTimeout(resolve, Math.max(1, remaining))),
        ]);
      } else {
        await new Promise((resolve) => setTimeout(resolve, Math.max(1, Math.min(5, remaining))));
      }
    }
  }

  // Wait until THIS event's own in-flight bracket has ended, so a switch is
  // attempted from outside it.
  //
  // A deferred relay is performed from `agent_end`, and OMP emits that event
  // inside `#endInFlight`, before the prompt's bracket is released. A commit
  // attempted there reads `SESSION_STREAMING` — the host's own answer about a
  // state that clears only after the handler returns. The handler therefore
  // yields to the event loop, where the host's own code releases the bracket,
  // and checks again.
  //
  // The wait is bounded and reports what it saw. It never awaits the host's
  // `waitForIdle()` for this bracket: that promise settles only after this very
  // handler returns (a deadlock), and the host would cut the handler off at its
  // own 30s limit anyway.
  //
  // A surface whose liveness comes from a live session object (a lane) needs no
  // separation: its `isStreaming` is the session's own fact, so this answers at
  // once without waiting.
  async waitOutsideOwnTurn({ maxPauseMs = 1000 } = {}) {
    if (ownTurnCleared(this.#session)) return { ok: true, waits: 0 };
    const cap = Number.isFinite(maxPauseMs) && maxPauseMs > 0 ? maxPauseMs : 0;
    const startedAt = Date.now();
    let waits = 0;
    while (!ownTurnCleared(this.#session)) {
      if (Date.now() - startedAt >= cap) {
        return { ok: false, reason: "OWN_TURN_NOT_RELEASED", waits, busy: this.reason() };
      }
      // Yielding to the event loop is what lets the host finish `#endInFlight`.
      await new Promise((resolve) => setTimeout(resolve, 0));
      waits += 1;
    }
    return { ok: true, waits };
  }

  // Hand control back to the operator (spec §3.3 example 7).
  //
  // `this.ask(question)` is `this.park` + `interrupt`: the program is already
  // suspended, and this ends the agent turn so the operator can read the
  // question and reply in ordinary chat. The next message resumes the parked
  // program through `unpark(name, answer)`.
  //
  // `abort()` is the host's own interrupt (`extensions/types.ts`
  // `ExtensionContext.abort`), read from the ctx of the emit that carries it —
  // never from a ctx captured at `session_start` (each emit builds a new one).
  // A surface without it (a lane, a test double) answers `NO_ABORT` instead of
  // pretending the turn stopped.
  async interruptTurn({ reason = "the operator was asked a question" } = {}) {
    const target = this.#session;
    if (target === null || typeof target !== "object") return { ok: false, reason: "NO_SESSION" };
    // The ctx carries `abort`; a lane's live session carries it too.
    const live = liveSession(target);
    const abort = typeof target.abort === "function" ? target.abort : live?.abort;
    if (typeof abort !== "function") return { ok: false, reason: "NO_ABORT" };
    try {
      await abort.call(typeof target.abort === "function" ? target : live, { reason });
    } catch (error) {
      return { ok: false, reason: "ABORT_FAILED", detail: String(error?.message ?? error) };
    }
    return { ok: true, interrupted: true, reason };
  }
}
