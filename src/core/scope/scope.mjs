// Scope for one session (spec §6.2).
//
// One `Scope` owns the facts of one session: the newest reset, the retention
// budget, the turn ledger, the records to persist and the projection input.
// It never runs model code, never reads files and never talks to a host: a
// store is injected, and a store is `append(records)` returning a promise.
//
// Ordering of one tool call (spec §6.2):
//   1. `admit` checks annotation presence and the budget; nothing changes;
//   2. `settle` books the true annotation once per turn;
//   3. the records are appended; a failed append is reported, not repaired;
//   4. `project` rebuilds the request copy.
//
// This package imports nothing outside its own directory: it carries its own
// refusal type, argument error and annotation parser, so the other core
// packages can change without touching Scope, and Scope needs no entry in the
// core package index.

import { Admission } from "./admission.mjs";
import { ScopeBook, Retention } from "./ledger.mjs";
import { SCOPE_CODES, SCOPE_KINDS, invalidArgument, refuse } from "./errors.mjs";
import {
  ColdState,
  budgetRecord,
  keepRecord,
  meTooRecord,
  readColdState,
  removeRecord,
  resetRecord,
  coldStartNotice,
} from "./records.mjs";
import { projectRequest } from "./projection.mjs";

const memoryStore = () => {
  const written = [];
  return {
    written,
    async append(records) {
      for (const entry of records) written.push(entry);
    },
  };
};

export class Scope {
  #sessionId;
  #queue;
  #inFlight;

  constructor({
    sessionId,
    admission = new Admission(),
    store = memoryStore(),
    book = new ScopeBook(),
    erase = [],
    cold = null,
  }) {
    if (typeof sessionId !== "string" || sessionId.length === 0) {
      throw invalidArgument("scope needs a non-empty sessionId");
    }
    if (typeof store.append !== "function") {
      throw invalidArgument("scope needs a store with append(records)");
    }
    this.#sessionId = sessionId;
    this.book = book;
    this.admission = admission;
    this.store = store;
    // Erases inherited from the record stream (a cold start). They never
    // change; beats erased in this process live on the book.
    this.erase = Object.freeze([...erase]);
    this.cold = cold;
    // One settle at a time. Admission, the clone and the append all read or
    // write the same facts, so two calls must never interleave: without this,
    // two clones of the same book would each spend a unit and one would be
    // lost, and a `meToo` staged on one clone would vanish with it.
    this.#queue = Promise.resolve();
    this.#inFlight = 0;
  }

  // Calls that are queued or settling right now.
  get inFlight() {
    return this.#inFlight;
  }

  // Run `work` after every earlier settle finished. The tail never rejects, so
  // one failed call cannot poison the queue for the next.
  #serial(work) {
    this.#inFlight += 1;
    const run = this.#queue.then(work, work);
    this.#queue = run.then(
      () => {},
      () => {},
    );
    return run.finally(() => {
      this.#inFlight -= 1;
    });
  }

  // A Scope for a process that just started. `records` are what the store
  // returned for this session. A persisted reset proves only that an old event
  // existed; the budget stays unknown until this process establishes one.
  static coldStart({
    sessionId,
    records = [],
    admission = new Admission(),
    store,
  } = {}) {
    const cold = readColdState(records);
    // `cold.remaining` is the newest budget fact the stream proves: the reset's
    // `budget` record, or a `keep` spend recorded after it. It is used only
    // when the budget is known — before that the model is told to resetScope
    // and no in-memory default stands in for the missing fact (spec §6.2).
    const retention = cold.budgetKnown
      ? new Retention({ established: true, remaining: cold.remaining })
      : new Retention();
    const book = new ScopeBook({ retention });
    // The erased beats come from the record stream: a cold start must rebuild
    // the same request copy the previous process would have produced.
    const newest = cold.newestReset;
    if (newest !== null) book.restoreResetBeat(newest);
    // The spin count is a persisted fact too: every `remove` record carries the
    // count at the moment of the erase, and the projection writes it into the
    // replacement text. Without this, a restarted process would rewrite an
    // already-presented beat with "Spin count: 1" after it had reached 3.
    book.restoreSpin(cold.spin);
    return new Scope({ sessionId, admission, store, book, cold, erase: cold.erase });
  }

  get sessionId() {
    return this.#sessionId;
  }

  get retention() {
    return this.book.retention;
  }

  get lastReset() {
    return this.book.lastReset;
  }

  get spinCount() {
    return this.book.spinCount;
  }

  // Every reset that actually replaced the budget, in order.
  get resetBeats() {
    return this.book.resetBeats;
  }

  get erased() {
    // The committed erases come from the book, so a failed append leaves the
    // list exactly as it was.
    return Object.freeze([...this.erase, ...this.book.erases]);
  }

  get budgetKnown() {
    // A cold start stays unconfirmed until a budget record or a reset that
    // settled in this process establishes it (spec §6.2).
    if (this.cold !== null && this.cold !== undefined) return this.cold.budgetKnown;
    return this.retention.established;
  }

  // The notice every request carries while the budget is unknown (spec
  // §6.2). `null` once a resetScope in this process established it.
  notice() {
    const cold = this.cold;
    if (cold === null || cold === undefined) {
      // A Scope opened in this process: the first reset established the budget,
      // and nothing was inherited to doubt.
      return this.retention.established ? null : coldStartNotice(ColdState.empty());
    }
    // A cold start keeps asking until a budget record confirms the current
    // Scope, even after an `assume-spent` default established a stand-in zero.
    if (cold.budgetKnown) return null;
    return coldStartNotice(cold);
  }

  // Step 1. Returns `{ ok: true, exempt, annotation }` or a refusal. Nothing
  // is written and no counter moves.
  admit({ member, annotation }) {
    return this.admission.check({
      member,
      annotation,
      retention: {
        established: this.retention.established,
        spent: this.retention.spent,
        remaining: this.retention.remaining,
      },
    });
  }

  // Steps 2 and 3: settle the true annotation and persist the records it
  // produced. Resolves to the settle result plus `persisted: true`, or to the
  // refusal. A failed append resolves with `persisted: false` and leaves every
  // fact exactly as it was.
  //
  // The settle happens on a copy of the book. Only after the records are
  // written does the copy replace the live facts, so memory can never be ahead
  // of the persisted record: a retry after a failed append books the beat once,
  // not twice, and a restart rebuilds the same copy the running process shows.
  call(request) {
    return this.#serial(() => this.#settleCall(request));
  }

  // The body of one settle. It runs only inside the queue, so admission, the
  // clone and the append cannot interleave with another call.
  async #settleCall({ member, annotation, turnId, callId = null, observed = null }) {
    const admitted = this.admit({ member, annotation });
    if (admitted.ok === false) return admitted;

    // The exempt members answer from the newest snapshot; they never park and
    // never settle, so the turn is not touched either.
    if (admitted.exempt) {
      return { ok: true, outcome: "exempt", kind: null, callId };
    }

    // Booking is per model turn (spec §6.2). A callId is not a turn: two
    // calls in one turn share a turn, so inventing a turn per call would spend
    // retention twice for one beat. Without the host's turn identity the call
    // is refused, and nothing is booked.
    const next = this.book.clone();
    const result = next.settle(turnId, admitted.annotation, { observed });
    if (result.ok === false) return result;

    const records = this.#recordsFor(next, result, turnId, callId);
    try {
      await this.store.append(records);
    } catch (error) {
      return {
        ok: false,
        code: SCOPE_CODES.SCOPE_RECORD_FAILED,
        message: "scope records could not be appended; no fact was changed",
        details: {
          sessionId: this.#sessionId,
          reason: String(error?.message ?? error),
          turnId,
          retryable: true,
        },
        persisted: false,
      };
    }
    // The live object must stay the same instance every reader holds (hooks
    // and the projection captured it); only its facts are replaced.
    this.book.adopt(next);
    // A reset that settled here confirms the budget for this process; the
    // cold-start notice stops (spec §6.2).
    if (result.outcome === "settled" && result.kind === SCOPE_KINDS.RESET) {
      if (this.cold !== null && this.cold !== undefined) this.cold.confirm();
    }
    return { ...result, turnId, persisted: true, records };
  }

  // The records a result produces, applied to the transaction copy.
  // Only a settled beat changes the facts. A staged `meToo` is provisional: it
  // is booked when the turn's true annotation settles, and refused outright
  // when the turn closes with nothing but `meToo` (spec §6.2, §6.2).
  #recordsFor(book, result, turnId, callId) {
    if (result.outcome === "confirmed") {
      return result.kind === "meToo" ? [meTooRecord({ turnId })] : [];
    }
    if (result.outcome !== "settled") return [];
    switch (result.kind) {
      case "resetScope":
        // A settled reset is the beat that opens the current Scope; the
        // physical callId is known only here (spec §6.2).
        book.noteResetBeat(callId);
        return [
          resetRecord({
            turnId,
            callId,
            goal: result.goal,
            budget: result.budget,
            remaining: result.remaining,
          }),
          budgetRecord({ remaining: result.remaining }),
        ];
      case "keepLast":
        return [keepRecord({ turnId, remaining: result.remaining })];
      case "removeLast":
        if (callId === null || callId === undefined) {
          // Without a callId the erased beat cannot be named in a later
          // projection. The turn stays settled, but the erase is not recorded.
          return [];
        }
        book.noteErase({ callId, replacement: result.replacement, spin: result.spin });
        return [
          removeRecord({
            turnId,
            callId,
            replacement: result.replacement,
            spin: result.spin,
          }),
        ];
      default:
        return [];
    }
  }

  // Step 4. A request copy; the input is not mutated.
  project(items, options = {}) {
    return projectRequest(items, {
      erase: this.erased,
      resetCallId: this.book.lastResetBeat?.callId ?? null,
      ...options,
    });
  }

  // Close the current model turn. A turn that only ever saw `meToo` is
  // refused here (spec §6.2 "全 meToo") and nothing is booked.
  //
  // The hook is synchronous because the hosts call it from a non-async event
  // path, but the answer must describe a quiescent book: while a settle is in
  // flight its beat is not committed yet, and judging the turn now could
  // refuse a turn that is about to settle. So the answer is deferred until the
  // queue drains, and the caller gets a promise. With nothing in flight the
  // promise is already settled with the same value a direct call would give.
  closeTurn() {
    if (this.#inFlight === 0) return Promise.resolve(this.book.closeTurn());
    return this.#serial(() => this.book.closeTurn());
  }


  // A synchronous answer from the newest snapshot (spec §6.2: `negotiate`
  // and `askPreviousScope` answer synchronously, from the cache-friendly
  // newest state; they never park).
  //
  // "Newest" means the newest COMMITTED fact. A settle in flight holds its
  // result only on a clone (see `#settleCall`), so an answer taken now would
  // describe a book that is about to change — the model would read a budget
  // one beat out of date and plan the next turn on it. The snapshot is
  // therefore refused while the queue is busy instead of answered from a
  // half-book: the caller asks again, and both members stay synchronous.
  #snapshotReady() {
    if (this.#inFlight === 0) return null;
    return refuse(
      SCOPE_CODES.SCOPE_SNAPSHOT_BUSY,
      "a call is still settling; ask again once it has committed",
      { sessionId: this.#sessionId, inFlight: this.#inFlight },
    );
  }

  negotiate() {
    const busy = this.#snapshotReady();
    if (busy !== null) return busy;
    const retention = this.retention;
    return Object.freeze({
      ok: true,
      kind: "negotiate",
      sessionId: this.#sessionId,
      goal: this.lastReset?.goal ?? null,
      budget: this.lastReset?.budget ?? null,
      remaining: retention.remaining,
      established: retention.established,
      mustReset: !retention.established || retention.spent,
      spin: this.spinCount,
    });
  }

  askPreviousScope() {
    const busy = this.#snapshotReady();
    if (busy !== null) return busy;
    const reset = this.lastReset;
    return Object.freeze({
      ok: true,
      kind: "askPreviousScope",
      sessionId: this.#sessionId,
      goal: reset?.goal ?? null,
      budget: reset?.budget ?? null,
      remaining: this.retention.remaining,
      turnId: reset?.turnId ?? null,
    });
  }
}

// The per-process table of sessions. Creating one session twice in one process
// is a wiring error, not a second Scope: the host must hand the same Scope to
// every hook of the same session.
export class ScopeTable {
  #scopes;

  constructor() {
    this.#scopes = new Map();
  }

  create(options) {
    const { sessionId } = options;
    if (this.#scopes.has(sessionId)) return this.taken(sessionId);
    const scope = new Scope(options);
    this.#scopes.set(sessionId, scope);
    return scope;
  }

  coldStart(options) {
    const { sessionId } = options;
    if (this.#scopes.has(sessionId)) return this.taken(sessionId);
    const scope = Scope.coldStart(options);
    this.#scopes.set(sessionId, scope);
    return scope;
  }

  taken(sessionId) {
    return refuse(SCOPE_CODES.SCOPE_OWNER_TAKEN, `session ${sessionId} already has a scope`, {
      sessionId,
    });
  }

  get(sessionId) {
    return this.#scopes.get(sessionId) ?? null;
  }

  delete(sessionId) {
    return this.#scopes.delete(sessionId);
  }
}
