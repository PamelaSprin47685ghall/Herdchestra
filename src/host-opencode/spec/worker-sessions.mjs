// Host sessions behind `fork` and `join` (§4.1, §7.1).
//
// `fork(name, brief, role)` is fire-and-forget: it creates a child session with
// the host, sends the brief with `delivery: "queue"` so the child starts on its
// own turn, and returns a receipt at once. Reusing a live name sends the brief
// to that child instead and says `steered: true`.
//
// `join()` waits for the first child that goes idle. The host has no "first one
// finished" primitive, so the adapter asks every live child with `Session.wait`
// and takes the first to answer. The outcome is read from the child's record
// afterwards.
//
// The host API is injected as `sessions` (the Promise `SessionDomain`). This
// module never imports OpenCode, so its mapping is testable with a fake.
//
// ## The roster is durable, not process-local
//
// A worker is a host session. It outlives the plugin that started it: OpenCode
// reloads a plugin whenever a watched file changes, and the server keeps the
// child sessions running. Measured on 2026-11-01 (session
// `ses_e6ab649c8ffea0B023Dy3Aj2P0`): four workers were forked, two were joined,
// the plugin reloaded at 04:09:44Z, and every later `join` answered "nothing to
// collect" in 1 ms while `devops` and `coder-hosts` were still running.
//
// So every fact `join` decides on is written to the session's own journal
// (`.Herdchestra/{session}.jsonl`, the file `session-state.mjs` already owns)
// and read back by a table that starts after a reload:
//
//   worker-fork     a worker was registered for this session
//   worker-settle   the host reported that session finished, with its outcome
//   worker-steer    the brief went to a live worker of the same name
//   worker-claim    a `join` handed the result to the caller
//   worker-release  a finished worker started a new session under the same name
//
// A restored roster is not trusted blindly: the host is the authority on what a
// session is doing now, so the first `join` reconciles every live worker against
// `sessions.get`. A worker that finished while the plugin was away becomes a
// result waiting to be claimed; one that is still running is waited on.

const LIVE = "running";
const IDLE = "idle";

export const WORKER_RECORD_TYPES = Object.freeze({
  FORK: "worker-fork",
  SETTLE: "worker-settle",
  STEER: "worker-steer",
  CLAIM: "worker-claim",
  RELEASE: "worker-release",
});

const isName = (value) => typeof value === "string" && value.length > 0;
const isSessionID = (value) => typeof value === "string" && value.length > 0;

// The record a roster entry is written as, and read back from. One shape for
// both directions keeps a restored entry and a fresh one from drifting.
const forkRecord = (worker) => ({
  type: WORKER_RECORD_TYPES.FORK,
  name: worker.name,
  role: worker.role ?? null,
  sessionID: worker.sessionID,
});

// Whether the host reports this session as finished, and with what outcome.
//
// The host records the outcome of the LAST completed run and keeps it until the
// next run completes (`session_v2.idle_outcome`), so a present outcome does not
// prove the current turn is over: a steered worker still carries the outcome of
// the turn before it. The turn in flight is what `time_busy > time_idle` says
// (`Session.Info`), so that comparison decides "finished now".
export const settledOutcomeOf = (session) => {
  if (session === null || typeof session !== "object") return { settled: false };
  const outcome = session.outcome;
  if (outcome === undefined || outcome === null) return { settled: false };
  const busy = session.time?.busy;
  const idle = session.time?.idle;
  // Without a busy clock the outcome is all this host offers, and reading it as
  // "finished" is the same answer the old adapter gave.
  if (typeof busy !== "number" || typeof idle !== "number") return { settled: true, outcome };
  if (busy > idle) return { settled: false };
  return { settled: true, outcome };
};

export class WorkerTable {
  #sessions;
  #onCreated;
  #onRecord;
  #workers = new Map();
  #unclaimed = [];
  // Set while the roster restored from the journal has not been checked against
  // the host yet. `join` reconciles first, so a worker that finished during a
  // reload becomes claimable instead of staying "live" forever.
  #needsReconcile = false;
  // The reconciliation in flight, so two callers share one round of host reads.
  #reconciling = null;
  // `join()` waits on this promise, never on a snapshot of the roster taken
  // when it was called. A worker that comes back while `join` waits resolves it,
  // and so does a roster adopted after a relay (根 §2.4): the successor's
  // `join()` must be able to receive the predecessor's finished worker, which
  // entered the table only when the successor first asked for it.
  #arrivals = [];
  #arriving = null;
  // How many times this table has changed. `join` reads it before it waits and
  // compares it after: a wait that answers without a change did not tell the
  // table anything new, and re-arming on it would spin.
  #generation = 0;

  // Wake every waiter. One wake per arrival is enough: each waiter re-checks
  // the table and re-arms if nothing is claimable, so a wake that two waiters
  // share costs one extra check, never a lost arrival.
  #wake() {
    this.#generation += 1;
    const waiters = this.#arrivals;
    this.#arrivals = [];
    for (const wake of waiters) wake();
  }

  // How many times the roster has changed since this table was built.
  get generation() {
    return this.#generation;
  }

  // Wait for the host to say this session stopped running, and for the table to
  // have changed afterwards.
  //
  // `Session.wait` resolves when the process owns no active execution for the
  // session — which is also true for a session that is idle right now and for
  // one whose turn has not started yet. Waiting on such a session again returns
  // at once, and a `join` that re-armed on that answer would spin on the
  // microtask queue forever, allocating a promise per iteration until the
  // process dies. So an answer that leaves the table unchanged is not a reason
  // to ask again: the caller waits for the next arrival instead (a completion
  // event, a fork, a steer, a relay's adoption), or for the next macrotask.
  async #awaitHostOrChange(sessionID) {
    const before = this.#generation;
    await this.#sessions.wait({ sessionID });
    if (this.#generation !== before) return "changed";
    // The host answered without the table learning anything. One macrotask
    // bounds how often a session can be re-read, and lets a completion event
    // that is already queued in the host's stream run first.
    await new Promise((resolve) => setTimeout(resolve, 0));
    return this.#generation === before ? "idle-answer" : "changed";
  }

  // The promise one arrival resolves, or `null` when a claimable entry already
  // exists. Nothing polls: the host's own `Session.wait` still decides that a
  // running worker came back, and this promise only decides that `join` should
  // look again.
  #nextArrival() {
    if (this.#arriving === null) {
      this.#arriving = new Promise((resolve) => {
        this.#arrivals.push(() => {
          this.#arriving = null;
          resolve();
        });
      });
    }
    return this.#arriving;
  }

  // One journal record, or nothing when this table has no journal (a test, or a
  // host that did not name a workspace). A failed append is reported to the
  // caller: a roster that did not reach the disk is not a roster the next
  // activation may trust.
  #record(entry) {
    if (typeof this.#onRecord !== "function") return;
    this.#onRecord(entry);
  }

  // `onCreated(sessionID, fact)` records a session this table just created,
  // before its first prompt (`identity.mjs`). It is optional so a test can build
  // the table without the plugin's registration.
  //
  // `onRecord(entry)` appends one roster record to the session's journal, and
  // `restored` is what that journal already held when this table was built. Both
  // are optional: a table built without them is the in-memory roster of one
  // process, which is all a test needs.
  constructor({ sessions, onCreated = null, onRecord = null, restored = null } = {}) {
    this.#sessions = sessions;
    this.#onCreated = typeof onCreated === "function" ? onCreated : null;
    this.#onRecord = typeof onRecord === "function" ? onRecord : null;
    if (restored !== null && restored !== undefined) this.#restore(restored);
  }

  // Rebuild the roster from the journal's records. The last record for a name
  // wins: a release followed by a fork is a new session, and a settle followed
  // by a claim is a result somebody already read.
  #restore(restored) {
    const entries = Array.isArray(restored?.entries) ? restored.entries : [];
    const unclaimed = Array.isArray(restored?.unclaimed) ? restored.unclaimed : [];
    for (const entry of entries) {
      if (!isName(entry?.name) || !isSessionID(entry?.sessionID)) continue;
      this.#workers.set(entry.name, {
        name: entry.name,
        role: typeof entry.role === "string" ? entry.role : null,
        sessionID: entry.sessionID,
        state: entry.state === IDLE ? IDLE : LIVE,
        outcome: entry.outcome ?? null,
        title: entry.title ?? null,
        // A steered worker's record is stale until the host reports the next
        // terminal; the restored entry keeps that fact.
        ...(entry.steered === true ? { steered: true } : {}),
        restored: true,
      });
    }
    for (const entry of unclaimed) {
      if (!isSessionID(entry?.sessionID)) continue;
      if (this.#unclaimed.some((existing) => existing.sessionID === entry.sessionID)) continue;
      this.#unclaimed.push(
        Object.freeze({
          name: typeof entry.name === "string" ? entry.name : null,
          role: typeof entry.role === "string" ? entry.role : null,
          sessionID: entry.sessionID,
          outcome: entry.outcome ?? null,
          title: entry.title ?? null,
        }),
      );
    }
    // Anything restored must be checked against the host before this table
    // reports on it: a worker that finished while no plugin was loaded is a
    // result nobody joined, and a road-bound devops that finished has released
    // the road. The check runs once, on the first `join` (or on the first
    // caller that asks, like the activation binding the road).
    this.#needsReconcile = [...this.#workers.values()].some((worker) => worker.restored === true);
  }

  // Read one session from the host, in the shape `Session.Info` has.
  async #sessionInfo(sessionID) {
    try {
      const answer = await this.#sessions.get({ sessionID });
      return answer?.data ?? answer ?? null;
    } catch {
      // A host that cannot answer keeps the worker live: `join` waits on the
      // session instead of inventing an outcome for it.
      return null;
    }
  }

  // The host's answer for one worker, applied to the table. A finished session
  // becomes a result waiting to be claimed; a running one stays live.
  //
  // A steered worker is the one case where the host's record is not the whole
  // truth: its `outcome` is the PREVIOUS turn's, and the host keeps it until the
  // new turn reaches a terminal. The table knows a steer was delivered, so it
  // does not read that outcome as this turn's answer — the session is waited on
  // until the host reports a terminal for it (`time.busy <= time.idle`), and
  // `settledOutcomeOf` is only asked once that turn is over.
  #applyHostAnswer(worker, session) {
    if (worker.state !== LIVE) return;
    if (worker.steered === true) {
      if (session === null || typeof session !== "object") return;
      const busy = session.time?.busy;
      // An absent idle mark means the session has not been idle since it
      // started, so a busy mark beside it is a turn in flight. Only a host that
      // reports no busy clock at all leaves the record as the only answer.
      if (session.time?.idle === null || session.time?.idle === undefined) {
        if (typeof busy === "number") return;
        const verdict = settledOutcomeOf(session);
        if (verdict.settled !== true) return;
        worker.steered = false;
        this.#arrive(worker, { outcome: verdict.outcome, title: session?.title ?? null });
        return;
      }
      const idle = session.time.idle;
      // No clock to compare: the record is all this host offers, and reading it
      // as "finished" is the same answer a worker that was never steered gets.
      if (typeof busy !== "number" || typeof idle !== "number") {
        const verdict = settledOutcomeOf(session);
        if (verdict.settled !== true) return;
        worker.steered = false;
        this.#arrive(worker, { outcome: verdict.outcome, title: session?.title ?? null });
        return;
      }
      // Still running: nothing has finished since the steer.
      if (busy > idle) return;
      // The host says the turn is over, so its outcome is this turn's terminal.
      const outcome = session.outcome ?? null;
      if (outcome === null) return;
      worker.steered = false;
      this.#arrive(worker, { outcome, title: session?.title ?? null });
      return;
    }
    const verdict = settledOutcomeOf(session);
    if (verdict.settled) this.#arrive(worker, { outcome: verdict.outcome, title: session?.title ?? null });
  }

  // Ask the host about every worker the journal restored, once. A worker that
  // finished while no plugin was loaded is a result nobody joined; a worker
  // that is still running keeps its name and is waited on.
  //
  // Public because a caller that reads the roster before `join` — the
  // activation binding the road-bound `devops` — needs the same answer, and a
  // second, private copy of it could disagree. Idempotent: a reconciled table
  // does nothing the second time.
  async reconcile() {
    if (this.#needsReconcile !== true) return this.#reconciling ?? undefined;
    return this.#reconcileOnce();
  }

  // One reconciliation per table. Two callers that ask at the same moment share
  // the same host reads, and the second one gets the finished answer instead of
  // starting a second round.
  #reconcileOnce() {
    if (this.#reconciling === null) {
      this.#reconciling = this.#reconcile().finally(() => {
        this.#reconciling = null;
      });
    }
    return this.#reconciling;
  }

  async #reconcile() {
    this.#needsReconcile = false;
    const candidates = [...this.#workers.values()].filter((worker) => worker.restored === true);
    for (const worker of candidates) {
      const session = await this.#sessionInfo(worker.sessionID);
      worker.restored = false;
      // Only a worker the table held as live is a candidate for "finished while
      // we were away": one the journal already recorded as idle finished while
      // a plugin was watching, and its outcome is not news.
      if (worker.state === LIVE) this.#applyHostAnswer(worker, session);
      // The host's own answer, recorded whether or not it changed anything, so a
      // caller binding the road reads the host's word rather than the journal's.
      worker.hostFinished = settledOutcomeOf(session).settled === true;
    }
  }

  get size() {
    return this.#workers.size;
  }

  get(name) {
    return this.#workers.get(name) ?? null;
  }

  names() {
    return [...this.#workers.keys()];
  }

  isLive(name) {
    return this.#workers.get(name)?.state === LIVE;
  }

  // How many workers are still running. This is the fact §2.3 reads when a
  // Manager closes: "the work is done" is only true when nothing it started is
  // still working. Idle and finished workers do not count — their process is
  // over, and their result is either claimed or waiting in the queue.
  liveCount() {
    let count = 0;
    for (const worker of this.#workers.values()) if (worker.state === LIVE) count += 1;
    return count;
  }

  // Start or steer. `role` is already validated by the caller.
  // `parentID` is the forking session: the child's family, and the session that
  // receives its completion (§7.1 `Session.create({ parentID })`).
  async fork({ name, brief, role, parentID, model, title }) {
    // The worker name travels in `title` (Session.create's title) and in the
    // table; it is also the receipt's name. A caller that passes only a title
    // still gets a named worker.
    const workerName = name ?? title;
    const existing = this.#workers.get(workerName);
    if (existing !== undefined && existing.state === LIVE) {
      // §7.1: a same-name live worker is STEERED, and the host's steer delivery
      // is `"steer"`. `"queue"` is the new-fork delivery: it admits the brief
      // only after the worker's current turn ends, so a worker that keeps
      // working never reads it while the receipt still says `steered: true`.
      await this.#sessions.prompt({ sessionID: existing.sessionID, text: brief, delivery: "steer" });
      // The steered turn is new work on a session that may still carry the
      // outcome of its previous turn (the host writes `idle_outcome` only at a
      // terminal). Forgetting it here is what keeps a later `join` from
      // reporting the turn that already finished as this one's answer.
      existing.outcome = null;
      // The session still carries the previous turn's outcome until the host
      // records the next terminal; the table remembers that this worker's
      // record must not be trusted until then.
      existing.steered = true;
      // Recording the steer is what lets a reloaded activation know this
      // session is busy again, and withdraw the stale result it may have
      // restored from the journal.
      this.#record({ type: WORKER_RECORD_TYPES.STEER, name: workerName, sessionID: existing.sessionID });
      return { name: workerName, steered: true, started: false };
    }
    const child = await this.#sessions.create({
      ...(parentID === undefined || parentID === null ? {} : { parentID }),
      title: title ?? workerName,
      agent: role,
      ...(model ? { model } : {}),
    });
    const sessionID = child?.data?.id ?? child?.id ?? null;
    if (typeof sessionID !== "string" || sessionID.length === 0) {
      throw new Error("the host returned no worker session id");
    }
    // Registered before the brief: the prompt that follows is this adapter's own
    // delivery, and a child's brief is never the operator's original input
    // (`identity.mjs`, `original-input.mjs`).
    this.#onCreated?.(sessionID, { parentID: parentID ?? null, role, origin: "fork" });
    const worker = { name: workerName, role, sessionID, state: LIVE, outcome: null, title: title ?? workerName };
    this.#workers.set(workerName, worker);
    // The roster reaches the disk BEFORE the brief: a reload between the two
    // steps must still know this session exists, or the worker would run with
    // nobody holding its name.
    this.#record(forkRecord(worker));
    await this.#sessions.prompt({ sessionID, text: brief, delivery: "queue" });
    // A worker that is already finished before it is registered still has to be
    // claimable: `join` waits on the roster's own arrival signal, and this is
    // that signal.
    this.#wake();
    return { name: workerName, steered: false, started: true, sessionID };
  }

  // The first child to go idle. `Promise.race` answers in arrival order, which
  // is what "first to come back" means (§4.1). The answer is a bare record; the
  // receipt shape belongs to the caller (steer-receipt.mjs).
  //
  // "First to come back" is decided by what the table HOLDS, not by who was in
  // the roster when `join` was called. An entry already claimable answers at
  // once; otherwise `join` waits for the host on every live worker AND for the
  // table's own arrival signal, and re-checks. Three things that happen after
  // the call therefore still reach it:
  //
  //   - a worker that was running now reports idle: the host's `Session.wait`
  //     answers, the worker is marked, and the next check claims it;
  //   - a finished result nobody joined — including one a relay carried into an
  //     adopted roster (根 §2.4) — is already an arrival: `settled` and `adopt`
  //     both wake this table;
  //   - a roster restored from the journal is checked against the host first,
  //     so a worker that finished during a plugin reload is claimable here.
  async join() {
    for (;;) {
      const claimed = this.#claim();
      if (claimed !== null) return claimed;
      if (this.#needsReconcile) {
        await this.#reconcile();
        continue;
      }
      const live = [...this.#workers.values()].filter((worker) => worker.state === LIVE);
      // Nothing to wait for: the roster holds no worker and no result. This is
      // the honest "nothing to collect" of §4.1, not a timeout — and it is only
      // reachable because the roster above was read back from the journal.
      if (live.length === 0 && this.#unclaimed.length === 0) return null;
      // Read every live worker before waiting, not only the one the host
      // answered for. A worker whose turn ended between two calls is already
      // finished, and a wait on its session would answer at once without
      // telling this loop anything: the record is where "finished" lives.
      for (const worker of live) {
        const session = await this.#sessionInfo(worker.sessionID);
        this.#applyHostAnswer(worker, session);
      }
      // An answer is a change only when it finished a worker; the loop claims it
      // at the top, so reading the same answer twice cannot grow the queue.
      if (this.#unclaimed.length > 0 || live.some((worker) => worker.state !== LIVE)) continue;
      const remaining = [...this.#workers.values()].filter((worker) => worker.state === LIVE);
      if (remaining.length === 0) continue;
      // Nothing polls and nothing spins. `#awaitHostOrChange` answers "changed"
      // only when the host said a session stopped AND the table learned
      // something; an answer that leaves the table as it was waits for the next
      // arrival instead of asking the same question again on the next microtask.
      // That is what keeps a host which reports an idle session as "not busy"
      // from turning `join` into an infinite loop.
      await Promise.race([
        ...remaining.map((worker) => this.#awaitHostOrChange(worker.sessionID)),
        this.#nextArrival(),
      ]);
      // The worker the host answered for leaves the live set before the next
      // check claims it. Reading its record is what turns "the session stopped
      // waiting" into an outcome, and the read is the host's own fact.
      //
      // The answer is not enough on its own: a host may report a session as
      // idle while its next turn is already queued, and a steered worker keeps
      // the outcome of the turn before it (`settledOutcomeOf`). Such a worker
      // stays live, and `join` waits again instead of reporting a worker that
      // never came back.
    }
  }

  // One worker finished. It leaves the live set and its result waits in the
  // claim queue until somebody joins it (根 §2.4: a result is retained until it is
  // claimed, not until the worker stops). The host's own title is kept, so a
  // `join` after the fact reports the same record the session has.
  #arrive(worker, session) {
    if (worker.state !== LIVE) return;
    worker.state = IDLE;
    worker.outcome = session?.outcome ?? null;
    worker.title = session?.title ?? worker.title ?? null;
    const entry = Object.freeze({
      name: worker.name,
      role: worker.role ?? null,
      sessionID: worker.sessionID,
      outcome: worker.outcome,
      title: worker.title,
    });
    this.#unclaimed = [...this.#unclaimed.filter((existing) => existing.sessionID !== worker.sessionID), entry];
    this.#record({
      type: WORKER_RECORD_TYPES.SETTLE,
      name: entry.name,
      sessionID: entry.sessionID,
      outcome: entry.outcome,
      title: entry.title,
    });
    this.#wake();
  }

  // The oldest unclaimed result, taken out of the queue. Arrival order is the
  // order workers finished, which is what "first to come back" means.
  #claim() {
    const [first] = this.#unclaimed;
    if (first === undefined) return null;
    this.#unclaimed = this.#unclaimed.slice(1);
    // The claim reaches the disk too: a reload after a result was handed over
    // must not offer it a second time.
    this.#record({ type: WORKER_RECORD_TYPES.CLAIM, name: first.name, sessionID: first.sessionID });
    return Object.freeze({
      name: first.name,
      role: first.role ?? null,
      sessionID: first.sessionID,
      outcome: first.outcome ?? null,
      title: first.title ?? null,
    });
  }

  // A finished worker is still addressable by name: reusing it starts a new
  // session under the same name (§4.1: steer applies to a live worker only).
  markFinished(name) {
    const worker = this.#workers.get(name);
    if (worker !== undefined && worker.state === LIVE) {
      worker.state = IDLE;
      // A finished worker is an arrival too: a `join` already waiting on this
      // table must re-check instead of waiting for a session that will never
      // send another event.
      this.#wake();
    }
    return worker ?? null;
  }

  // A session lifecycle event names the child session, not the worker. The
  // worker behind it leaves the live set, so a later `fork` of the same name
  // starts a new session instead of steering a finished one.
  settled(sessionID, outcome = null) {
    const worker = this.workerOfSession(sessionID);
    if (worker === null) return null;
    // The event names the session and its outcome; the record `join` reports is
    // built here, once, so an event-driven finish and a `wait`-driven finish
    // cannot disagree about what a finished worker means.
    this.#arrive(worker, { outcome });
    return worker;
  }

  // Results that finished but were never joined, in arrival order.
  unclaimed() {
    return [...this.#unclaimed];
  }

  // Take back results a predecessor left unclaimed (根 §2.4).
  adoptUnclaimed(entries) {
    const adopted = [];
    const refused = [];
    for (const entry of Array.isArray(entries) ? entries : []) {
      const sessionID = typeof entry?.sessionID === "string" ? entry.sessionID : null;
      if (sessionID === null) {
        refused.push({ sessionID: null, reason: "ADOPT_INCOMPLETE" });
        continue;
      }
      if (this.#unclaimed.some((existing) => existing.sessionID === sessionID)) continue;
      this.#unclaimed.push(
        Object.freeze({
          name: typeof entry.name === "string" ? entry.name : null,
          role: typeof entry.role === "string" ? entry.role : null,
          sessionID,
          outcome: entry.outcome ?? null,
        }),
      );
      adopted.push(sessionID);
    }
    if (adopted.length > 0) this.#wake();
    return { adopted, refused };
  }

  // The session behind a name, live or idle: the lane start needs it, and a
  // completion event names a session, not a worker.
  sessionIDOf(name) {
    return this.#workers.get(name)?.sessionID ?? null;
  }

  /**
   * Adopt a predecessor's workers across a relay (根 §2.4).
   *
   * The spec is explicit: a relay preserves accepted work, process ownership
   * and unclaimed results, and the road-bound `devops` does not change hands.
   * A worker is a host session that outlives its owner's tenure, so adopting it
   * means the successor holds the same name, role and session id — not that it
   * forks a replacement, which would abandon the work in flight and lose the
   * result nobody has read yet.
   *
   * Returns what it adopted and what it refused. A worker whose session id is
   * missing cannot be adopted at all: there would be no host session to hold.
   */
  adopt(entries) {
    const adopted = [];
    const refused = [];
    for (const entry of Array.isArray(entries) ? entries : []) {
      const name = typeof entry?.name === "string" ? entry.name : null;
      const sessionID = typeof entry?.sessionID === "string" ? entry.sessionID : null;
      if (name === null || sessionID === null) {
        refused.push({ name, sessionID, reason: "ADOPT_INCOMPLETE" });
        continue;
      }
      const existing = this.#workers.get(name);
      if (existing !== undefined && existing.sessionID !== sessionID) {
        // A live name already bound to another session is a collision, not an
        // adoption: rebinding it would silently drop the worker this table is
        // steering today.
        refused.push({ name, sessionID, reason: "ADOPT_NAME_TAKEN" });
        continue;
      }
      const entryState = {
        name,
        role: entry.role ?? null,
        sessionID,
        state: entry.state === IDLE ? IDLE : LIVE,
        outcome: entry.outcome ?? null,
        title: null,
        adopted: true,
      };
      this.#workers.set(name, entryState);
      this.#record(forkRecord(entryState));
      adopted.push(entryState);
    }
    if (adopted.length > 0) this.#wake();
    return { adopted, refused };
  }

  /**
   * The roster a relay must preserve (根 §2.4), in record shape.
   *
   * Not only the live workers: a relay keeps "accepted work, process ownership
   * and unclaimed results", and the road-bound `devops` does not change hands
   * even when it is idle between tasks. An idle worker is where a name still
   * points, and a finished one may hold a result nobody joined yet — dropping
   * either at the relay would make the successor redo work to learn what the
   * predecessor already knew.
   */
  rosterEntries() {
    return [...this.#workers.values()].map((worker) =>
      Object.freeze({
        name: worker.name,
        role: worker.role ?? null,
        sessionID: worker.sessionID,
        state: worker.state,
      }),
    );
  }

  // The worker whose session id this is, or null. A completion event carries
  // only the child session id, so the table is the way back to the name.
  workerOfSession(sessionID) {
    for (const worker of this.#workers.values()) {
      if (worker.sessionID === sessionID) return worker;
    }
    return null;
  }
}

/**
 * The roster a journal's records describe, in the shape `WorkerTable` restores
 * from.
 *
 * Records are replayed in order, one name at a time: a fork binds a name to a
 * session, a settle makes that session's result claimable, a steer means the
 * same session is busy again, a claim means the result was handed over, and a
 * release means the name now points somewhere else. The replay is the only
 * reader of these records, so the meaning of each one is decided here.
 *
 * A `worker-release` is written by a later `fork` that reuses a finished name;
 * `restoreRoster` therefore also drops a name whose latest fork names a
 * different session than its latest settle.
 */
export const restoreRoster = (records) => {
  const byName = new Map();
  const claimed = new Set();
  const unclaimed = [];
  const pushUnclaimed = (worker) => {
    if (claimed.has(worker.sessionID)) return;
    const index = unclaimed.findIndex((entry) => entry.sessionID === worker.sessionID);
    const entry = Object.freeze({
      name: worker.name,
      role: worker.role ?? null,
      sessionID: worker.sessionID,
      outcome: worker.outcome ?? null,
      title: worker.title ?? null,
    });
    if (index === -1) unclaimed.push(entry);
    else unclaimed[index] = entry;
  };
  for (const record of Array.isArray(records) ? records : []) {
    const type = record?.type;
    if (type === WORKER_RECORD_TYPES.FORK) {
      if (!isName(record.name) || !isSessionID(record.sessionID)) continue;
      byName.set(record.name, {
        name: record.name,
        role: typeof record.role === "string" ? record.role : null,
        sessionID: record.sessionID,
        state: LIVE,
        outcome: null,
        title: null,
      });
      continue;
    }
    if (type === WORKER_RECORD_TYPES.STEER) {
      const worker = byName.get(record.name);
      if (worker === undefined || worker.sessionID !== record.sessionID) continue;
      worker.state = LIVE;
      worker.outcome = null;
      // The session still carries the previous turn's outcome on the host's
      // record. A table rebuilt from this journal must know that, or its first
      // `join` would report the turn that already finished as this one's
      // answer (measured: a reload between a steer and its join did exactly
      // that).
      worker.steered = true;
      const index = unclaimed.findIndex((entry) => entry.sessionID === worker.sessionID);
      if (index !== -1) unclaimed.splice(index, 1);
      continue;
    }
    if (type === WORKER_RECORD_TYPES.SETTLE) {
      const worker = byName.get(record.name);
      if (worker === undefined || worker.sessionID !== record.sessionID) continue;
      worker.state = IDLE;
      worker.outcome = record.outcome ?? null;
      worker.title = record.title ?? worker.title;
      pushUnclaimed(worker);
      continue;
    }
    if (type === WORKER_RECORD_TYPES.CLAIM) {
      if (isSessionID(record.sessionID)) claimed.add(record.sessionID);
      const index = unclaimed.findIndex((entry) => entry.sessionID === record.sessionID);
      if (index !== -1) unclaimed.splice(index, 1);
      continue;
    }
    if (type === WORKER_RECORD_TYPES.RELEASE) {
      // The name moved to a session that has its own fork record; dropping the
      // old entry keeps a stale session from answering for the name.
      const worker = byName.get(record.name);
      if (worker !== undefined && worker.sessionID === record.sessionID) byName.delete(record.name);
    }
  }
  const entries = [...byName.values()];
  return { entries, unclaimed: unclaimed.filter((entry) => !claimed.has(entry.sessionID)) };
};

// Whether a journal record belongs to the worker roster.
export const isWorkerRecord = (record) =>
  typeof record?.type === "string" && Object.values(WORKER_RECORD_TYPES).includes(record.type);
