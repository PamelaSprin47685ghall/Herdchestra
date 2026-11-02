// Worker sessions behind `fork`, `join` and fission lanes (spec §4.1, §4.2).
//
// A worker is one delegated runtime started through the host factory this
// adapter resolves at run time. `fork` starts it and returns at once; the
// runtime's promise settles on its own and its record waits for the next
// `join()`. Reusing a live name steers that worker instead of starting a second
// session, and the receipt says which happened.
//
// A same-name `fork` onto a LIVE worker steers that worker's RUNNING turn
// (root §4.1): the handle's `steer` puts the verboseIntent into the runtime's own
// pending-input channel, which the agent loop reads at every request boundary
// (`runtime.mjs`). A delegated runtime refuses a second `run` while one is in
// flight (its run admission is `running -> reject`), and the plugin cannot
// reach the hub's `send_input` queue, so this channel is the one that exists —
// and it is a real mid-turn delivery, not a queue behind the turn. A handle
// that has no such channel keeps the verboseIntent for the next turn instead; the
// receipt is `{ name, steered, started }` either way.
//
// The host factory is injected. This module never imports `@cline/core`, so its
// mapping is testable with a fake, and a host that does not hand us the factory
// produces `started: false` rather than a pretend session.
//
// ## The roster is durable, and `join` decides on turns, not on names
//
// A delegated runtime lives in the host's process, and a plugin may be loaded
// again into that same process while its workers keep running. An in-memory
// roster then answers "nothing to collect" for work that is still in flight.
// So every fact `join` decides on is offered to `onRecord` (the session's
// journal, `.Herdchestra/<session>.workers.jsonl`) and a table built with
// `restored` starts from what that journal held:
//
//   fork     the name was registered for this session
//   steer    a verboseIntent entered a live worker's turn, or started its next turn
//   settle   a turn finished; the record waits for a `join`
//   claim    a `join` handed the record over
//   release  the runtime behind the name stopped (an aborted lane, a session end)
//
// ## The ledger
//
// `join` is stated over TURNS, not over names. One worker can have several
// turns: a steered verboseIntent queued behind the running one, or one accepted
// after the worker already settled once. Each turn is admitted when it is
// accepted and settled exactly once, and only a settled turn that nobody
// claimed is a completion. So:
//
//   owed = (admitted turns - settled turns) + completions nobody claimed
//
// `join` answers a record only when a completion exists, and answers `null`
// only when `owed` is zero. A steer that lands while the previous turn settles
// therefore cannot leave `join` thinking the work is over: its turn is already
// admitted, and the settle that ends the previous turn leaves the next one in
// flight.

import path from "node:path";

import { appendJsonLine, readJsonLines } from "./files.mjs";

const LIVE = "running";
const IDLE = "idle";
const DEAD = "dead";
// A present that fission replaced (spec §4.2 step 6): its turn is over, its
// queue is dropped, and it produces no further turn. The runtime object stays
// so its transcript is still readable.
const REPLACED = "replaced";

// The roster records this table writes and reads back. One shape for both
// directions keeps a restored entry and a fresh one from drifting.
export const WORKER_RECORD_TYPES = Object.freeze({
  FORK: "fork",
  STEER: "steer",
  SETTLE: "settle",
  CLAIM: "claim",
  RELEASE: "release",
  // Root §1.1 item 2: an operator interrupt reached this subtree. It is not a
  // release (the session lives on): unclaimed completions stay collectable, and
  // the record says why the turns that never report were settled.
  CANCEL: "cancel",
});

const isName = (value) => typeof value === "string" && value.length > 0;

// The outcome of one settled turn, read the same way in both directions.
const recordOf = (value) => {
  const source = value !== null && typeof value === "object" ? value : {};
  return Object.freeze({
    finishReason: typeof source.finishReason === "string" ? source.finishReason : "unknown",
    text: typeof source.text === "string" ? source.text : "",
    iterations: typeof source.iterations === "number" ? source.iterations : 0,
  });
};

// A count that a restored record may not carry. A record that names no number
// is read as zero, which is the honest reading of "the journal did not say".
const countOf = (value) => (Number.isInteger(value) && value >= 0 ? value : 0);

// What one worker's entry looks like from outside: the facts a journal can
// carry and a caller may read. The runtime handle never leaves this table.
const entryView = (entry) => ({
  name: entry.name,
  role: entry.role,
  state: entry.state,
  lane: entry.lane,
  admitted: entry.admitted,
  settled: entry.settled,
  pending: entry.pending.length,
  view: entry.view,
  restored: entry.restored,
  cancelled: entry.cancelled === true,
});

/**
 * The roster a journal's records describe, in the shape `WorkerTable` restores
 * from: `{ entries, completions, claims }`.
 *
 * Records are replayed in order, one name at a time. A `fork` binds a name and
 * zeroes its turn counters; a `steer` admits one more turn; a `settle` settles
 * one; a `claim` names the turn it took; a `release` stops the runtime behind
 * the name, which settles whatever turns it still owed — an aborted turn never
 * finishes, and a ledger that kept waiting for it would wait forever.
 *
 * `claims` are kept as a set of turn ids, not as a counter, so a journal that
 * lost a record cannot let one finished turn be handed over twice: the table
 * checks every claim against it.
 */
export const restoreRoster = (records) => {
  const byName = new Map();
  // Completions are keyed by turn id. A Map keeps both the lookup and the
  // insertion order: the old array + `findIndex` made every replayed settle a
  // linear scan, which is quadratic over one journal.
  const completions = new Map();
  const claims = new Set();
  const completionOf = (entry, outcome, settledTurn) => ({
    id: `${entry.name}#${settledTurn}`,
    name: entry.name,
    role: entry.role,
    turn: settledTurn,
    outcome,
    lane: entry.lane,
  });
  const pushCompletion = (entry, outcome, settledTurn = entry.settled) => {
    const id = `${entry.name}#${settledTurn}`;
    if (claims.has(id)) return;
    completions.set(id, Object.freeze(completionOf(entry, outcome, settledTurn)));
  };
  for (const record of Array.isArray(records) ? records : []) {
    if (record === null || typeof record !== "object") continue;
    const type = record.type;
    const name = record.name;
    if (!isName(name)) continue;
    if (type === WORKER_RECORD_TYPES.FORK) {
      byName.set(name, {
        name,
        role: typeof record.role === "string" ? record.role : null,
        lane: record.lane === true,
        state: record.state === DEAD ? DEAD : record.state === IDLE ? IDLE : LIVE,
        admitted: countOf(record.admitted),
        settled: countOf(record.settled),
        steered: countOf(record.steered),
        view: typeof record.view === "string" ? record.view : null,
        cancelled: record.cancelled === true,
      });
      continue;
    }
    const entry = byName.get(name);
    if (entry === undefined) continue;
    if (type === WORKER_RECORD_TYPES.STEER) {
      // A steered verboseIntent is a turn of this worker, carried by the turn that is
      // running: it is admitted, and the report that takes it settles both.
      entry.admitted = Math.max(entry.admitted + 1, countOf(record.admitted));
      // Only a `delivery: "steer"` rode the running turn. A `delivery: "turn"`
      // starts its own turn (an idle worker, or a restored one a verboseIntent starts),
      // so it is admitted but not carried: counting it as carried would subtract
      // it from `owed` a second time and answer `join` with nothing owed.
      if (record.delivery === "steer") entry.steered = (entry.steered ?? 0) + 1;
      entry.state = LIVE;
      continue;
    }
    if (type === WORKER_RECORD_TYPES.SETTLE) {
      // The record names the last turn this report settled; the steers it
      // carried are the turns before it.
      const carried = countOf(record.steered);
      entry.steered = Math.max(0, (entry.steered ?? 0) - carried);
      const turn = Number.isInteger(record.turn) ? record.turn : entry.settled + 1 + carried;
      entry.settled = Math.max(entry.settled, turn);
      const outcome = recordOf(record.outcome);
      if (outcome.text.length > 0) entry.view = outcome.text;
      // One report settles the turn that was running AND every steer it carried
      // (`#settle`): each of them is a completion. The carrier's own text is
      // recorded beside the report because a carried turn's answer is the
      // report, while the carrier's record is the text that turn was given.
      const carrierText = typeof record.carrierText === "string" ? record.carrierText : null;
      for (let settledTurn = Math.max(1, turn - carried); settledTurn <= turn; settledTurn += 1) {
        if (settledTurn > entry.admitted) continue;
        const text =
          carried > 0 && settledTurn === turn - carried && carrierText !== null ? carrierText : outcome.text;
        pushCompletion(entry, { ...outcome, text }, settledTurn);
      }
      if (entry.settled >= entry.admitted) entry.state = IDLE;
      continue;
    }
    if (type === WORKER_RECORD_TYPES.CLAIM) {
      // A claim names the turn it took. The completion it took must not be
      // offered again: the journal is replayed in order, and a claim can arrive
      // after its turn's settle (`join` claims the moment a turn ends).
      const id = typeof record.id === "string" ? record.id : `${name}#${countOf(record.turn)}`;
      claims.add(id);
      completions.delete(id);
      continue;
    }
    if (type === WORKER_RECORD_TYPES.RELEASE) {
      entry.state = DEAD;
      entry.settled = Math.max(entry.settled, entry.admitted);
      continue;
    }
    if (type === WORKER_RECORD_TYPES.CANCEL) {
      // The subtree was cancelled: every owed turn ends here, and each one is a
      // completion whose outcome names the cancellation. The turns nobody
      // claimed before this record stay in `completions` — they are results
      // that really arrived, and a cancel does not erase them.
      const last = Math.max(entry.settled, countOf(record.turn) || entry.admitted);
      const turn = Math.max(last, entry.admitted);
      // A lane's cancelled turn is not a `join` completion, exactly as in
      // `cancelAll`: its settlement belongs to a fission group, and a group has
      // no continuation after an operator interrupt.
      if (entry.lane !== true) {
        for (let settledTurn = entry.settled + 1; settledTurn <= turn; settledTurn += 1) {
          if (settledTurn <= entry.admitted) {
            pushCompletion(entry, recordOf({ finishReason: "cancelled", text: "" }), settledTurn);
          }
        }
      }
      entry.settled = Math.max(entry.settled, turn);
      entry.steered = 0;
      entry.state = DEAD;
      entry.cancelled = true;
      continue;
    }
  }
  // `claims` is an ARRAY in both directions: a reader (`WorkerTable#restore`)
  // iterates it, and a `Set` would answer no items to that loop.
  return { entries: [...byName.values()], completions: [...completions.values()], claims: [...claims] };
};

// The journal one session's roster is written to.
//
// `.Herdchestra/<session>.workers.jsonl` holds one record per change and is the
// ONE source a table is built from (root §1.1 item 1). The journal used to have
// a snapshot beside it; two files that can disagree are two answers to the same
// question, and a mid-session reload read the stale one. Every fact a reload
// needs is in the journal: a `release` says the runtime stopped, the `claim`s
// it writes say which completions were already handed over, and a `cancel`
// says which subtree the operator stopped.
export const workerRosterFile = (root, sessionId) => ({
  file: path.join(root, ".Herdchestra", `${sessionId}.workers.jsonl`),
});

// What a table built for this session starts from: the journal replayed, or
// null when it holds no record. A torn last line is a crash during append and
// carries no record (`readJsonLines` tolerates exactly that one line).
export const restoredRoster = (root, sessionId) => {
  if (typeof root !== "string" || typeof sessionId !== "string") return null;
  const { file } = workerRosterFile(root, sessionId);
  const records = readJsonLines(file);
  if (records.length === 0) return null;
  return restoreRoster(records);
};

export class WorkerTable {
  #create;
  #onRecord;
  #file;
  #workers = new Map();
  // Settled turns nobody claimed, in settlement order. This is the only queue
  // `join` answers from; the live workers are what it waits on. A Map keyed by
  // turn id, so a claim is O(1) and a journal replay does not scan the queue for
  // every settle (`#complete`, `#claim`, `unclaimed`).
  #completions = new Map();
  // Turn ids already handed over. A claim is checked against this set before a
  // record leaves the table, so no turn is ever answered twice.
  #claims = new Set();
  #waiters = [];
  // Every lane settlement, for the fission group that owns the lane. A lane is
  // not a worker `join` can collect, so its result reaches its group here.
  #laneListeners = [];
  // Bumped by every change a waiter must re-read. A wake that did not change
  // anything (a settle nobody waited for) must not make a waiter spin.
  #generation = 0;
  // The host's ceiling on delegated runtimes (spec §4.2 step 1). It is measured
  // by the session that owns the table (`fission.mjs` `LANE_CAPACITY`); a table
  // built without one answers no capacity at all, which refuses every group.
  #capacityLimit = null;
  // Why the roster could not be written, or `null`. A diagnostic: no decision
  // reads it.
  rosterWriteFailure = null;

  /**
   * `create({name, verboseIntent, role})` returns one of
   *   { started: true, run(verboseIntent) -> Promise<record>, abort(), onSettled(cb) }
   *   { started: false, rejection, message }
   * and may also be async. The runtime handle starts the first turn itself and
   * reports through `onSettled`; `run` is the steer path.
   *
   * `onRecord(record)` receives one roster record per change; it is optional,
   * and a table built without it is the in-memory roster of one process.
   * `restored` is an explicit override (a test, or a caller that already read
   * the journal): either the `{ entries, completions, claims }` shape
   * `restoreRoster` answers, or the raw records. Without it the table REPLAYS
   * its journal as it is built — one source, and the same source a reload reads.
   *
   * `root` and `sessionId` name the journal this table owns: without `file`,
   * the table writes `workerRosterFile(root, sessionId)`. A table given neither
   * keeps no file and calls `onRecord` only.
   */
  constructor({ create, onRecord = null, restored = null, file = null, root = null, sessionId = null, capacityLimit = null } = {}) {
    this.#create = create;
    this.#capacityLimit = Number.isInteger(capacityLimit) && capacityLimit > 0 ? capacityLimit : null;
    this.#onRecord = typeof onRecord === "function" ? onRecord : null;
    const named = file !== null ? null : typeof root === "string" && typeof sessionId === "string" ? workerRosterFile(root, sessionId) : null;
    this.#file = typeof file === "string" && file.length > 0 ? file : named?.file ?? null;
    if (restored !== null && restored !== undefined) {
      this.#restore(restored);
    } else if (this.#file !== null) {
      // The journal is append-only and complete: a `fork`/`steer`/`settle`/
      // `claim`/`release`/`cancel` record is written before the fact it
      // describes can be read back. A torn last line is a crash during append
      // and carries no record.
      const records = readJsonLines(this.#file);
      if (records.length > 0) this.#restore(records);
    }
  }

  // Rebuild the roster from the journal. The record form is replayed here, so a
  // caller may hand over raw records or the shape `restoreRoster` answers.
  #restore(restored) {
    const roster =
      Array.isArray(restored)
        ? restoreRoster(restored)
        : Array.isArray(restored?.entries) || Array.isArray(restored?.completions) || Array.isArray(restored?.claims)
          ? {
              entries: Array.isArray(restored.entries) ? restored.entries : [],
              completions: Array.isArray(restored.completions) ? restored.completions : [],
              claims: Array.isArray(restored.claims) ? restored.claims : [],
            }
          : { entries: [], completions: [], claims: [] };
    for (const claim of roster.claims) if (typeof claim === "string") this.#claims.add(claim);
    for (const entry of roster.entries) {
      if (!isName(entry?.name)) continue;
      const admitted = countOf(entry.admitted);
      const settled = countOf(entry.settled);
      this.#workers.set(entry.name, {
        name: entry.name,
        role: typeof entry.role === "string" ? entry.role : null,
        handle: null,
        // A restored runtime is not held by this process: it is either running
        // elsewhere and will report through a host event (`settled`), or it is
        // gone. Either way it is not DEAD until something says so.
        state: entry.state === DEAD ? DEAD : settled >= admitted ? IDLE : LIVE,
        view: typeof entry.view === "string" && entry.view.length > 0 ? entry.view : null,
        pending: [],
        last: null,
        lane: entry.lane === true,
        admitted,
        settled,
        // How many steered verboseIntents the running turn carries. The journal
        // has to keep it: a restored table that forgot it would owe a turn per
        // steer and wait for a report the host will never send (§5, the roster
        // is durable; root §4.1, `join` answers honestly).
        steered: countOf(entry.steered),
        verboseIntent: "",
        carrierText: null,
        // A cancelled subtree stays cancelled across a reload: a same-name fork
        // must refuse instead of starting a runtime the operator stopped.
        cancelled: entry.cancelled === true,
        restored: true,
      });
    }
    for (const completion of roster.completions) {
      if (!isName(completion?.name)) continue;
      // A lane's settled turn is its DELIVERY to its group (§4.2 step 8), never
      // a `join` completion: a restored table that queued one would hand a
      // lane's record to `join` as if a fork worker had finished.
      if (completion.lane === true) continue;
      const id = typeof completion.id === "string" ? completion.id : `${completion.name}#${countOf(completion.turn)}`;
      if (this.#claims.has(id) || this.#completions.has(id)) continue;
      this.#completions.set(
        id,
        Object.freeze({
          id,
          name: completion.name,
          role: typeof completion.role === "string" ? completion.role : null,
          turn: countOf(completion.turn),
          outcome: recordOf(completion.outcome),
          lane: completion.lane === true,
        }),
      );
    }
  }

  // One roster record: to the caller's journal, and to this table's own file
  // when it owns one. A failed write is not thrown at the host — the record
  // describes work that already happened — but `rosterWriteFailure` names it,
  // because a roster that did not reach the disk is not one the next load may
  // trust.
  #record(record) {
    const entry = { v: 1, ...record };
    if (this.#onRecord !== null) {
      try {
        this.#onRecord(entry);
      } catch (error) {
        this.rosterWriteFailure = { reason: "ROSTER_RECORD_FAILED", detail: String(error?.message ?? error) };
      }
    }
    if (this.#file !== null) {
      try {
        appendJsonLine(this.#file, entry);
      } catch (error) {
        this.rosterWriteFailure = { reason: "ROSTER_UNWRITABLE", detail: String(error?.message ?? error) };
      }
    }
    return entry;
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

  // Whether `name` is a worker `fork` started (spec §4.2 step 1). A lane is not:
  // fission never creates a new identity, and a group of a lane would be
  // recursion. The session itself is never in this table, so a top-level caller
  // is refused as `InvalidOrigin` by the same answer.
  isForked(name) {
    const worker = this.#workers.get(name);
    if (worker === undefined) return false;
    return worker.lane !== true && worker.state !== DEAD;
  }

  // The owner's last delivered view (spec §4.2 step 2): its last settled turn's
  // text, or null when it has not finished one. An empty view is not a refusal.
  viewOf(name) {
    const worker = this.#workers.get(name);
    if (worker === undefined) return null;
    return worker.view ?? null;
  }

  // How many turns nobody has claimed: admitted but not settled, plus settled
  // but not handed over. This is the fact `join` answers `null` on, and the one
  // a caller may read to check the ledger itself.
  owed() {
    // A settled turn nobody claimed is owed ONCE: it is a completion, and it is
    // already settled, so the per-worker arithmetic below must not count it
    // again (`join` answers it, then it leaves both).
    let owed = this.#completions.size;
    for (const worker of this.#workers.values()) {
      if (worker.lane === true || worker.state === DEAD) continue;
      // A turn this worker owes. A settled turn nobody claimed is counted once,
      // as a completion above; what is left here is what is still IN FLIGHT —
      // the turn that is running, plus every steered verboseIntent the runtime's queue
      // still holds (each is admitted and reports as its own turn).
      owed += Math.max(0, worker.admitted - worker.settled - (worker.steered ?? 0));
    }
    return owed;
  }

  // The roster as a reader sees it: every name with its turn counters, every
  // settled turn nobody claimed, every turn id already handed over. It is the
  // same shape the journal replays to, so a test can compare the two without a
  // second definition of "the roster". It is NOT a file: the journal is the one
  // durable source, and a snapshot beside it would be a second answer.
  snapshot() {
    return {
      entries: [...this.#workers.values()].map((worker) => ({
        name: worker.name,
        role: worker.role,
        state: worker.state,
        lane: worker.lane,
        admitted: worker.admitted,
        settled: worker.settled,
        steered: worker.steered ?? 0,
        view: worker.view,
        cancelled: worker.cancelled === true,
      })),
      completions: [...this.#completions.values()].map((completion) => ({
        id: completion.id,
        name: completion.name,
        role: completion.role,
        turn: completion.turn,
        outcome: completion.outcome,
        lane: completion.lane,
      })),
      claims: [...this.#claims],
    };
  }

  // Where the next load of this session reads the roster from. Every change was
  // appended as it happened, so there is nothing left to write: this answers the
  // journal's path, or `NO_ROSTER_FILE` for a table that owns none.
  save() {
    if (this.#file === null) return { ok: false, reason: "NO_ROSTER_FILE" };
    return { ok: true, path: this.#file };
  }

  async fork({ name, verboseIntent, role }) {
    // The role is compose's answer, read from the verboseIntent's own declaration
    // (§2.1, §4.1). A port that arrives without one is a wiring defect, and
    // starting a worker on a guessed identity would hand command execution to
    // the wrong participant; refuse instead of defaulting.
    if (role !== "coder" && role !== "devops") {
      return { name, steered: false, started: false, rejection: "UNDECLARED_ROLE" };
    }
    // 根 §2.1/§2.3: exactly one DevOps at a time. All real command execution is
    // serialized through that one worker, so a second live (or turn-owing)
    // devops is refused by name; the caller steers the existing one instead.
    // The same name is not a second worker: a same-name `fork` is a steer.
    if (role === "devops") {
      const active = [...this.#workers.values()].find(
        (worker) =>
          worker.name !== name &&
          worker.role === "devops" &&
          worker.state !== DEAD &&
          (worker.state === LIVE || worker.admitted > worker.settled),
      );
      if (active !== undefined) {
        return {
          name,
          steered: false,
          started: false,
          rejection: "DEVOPS_ALREADY_ACTIVE",
          message: `devops worker ${active.name} is still active; steer it instead of starting a second one`,
          detail: { name: active.name },
        };
      }
    }
    const existing = this.#workers.get(name);
    // Root §1.1 item 2: the operator stopped this subtree. Starting it again
    // (or feeding it a turn) would spend the tokens the interrupt was meant to
    // save, so every path refuses by name.
    if (existing !== undefined && existing.cancelled === true) {
      return {
        name,
        steered: false,
        started: false,
        rejection: "WORKER_CANCELLED",
        message: `worker ${name} was cancelled by an operator interrupt and produces no further turn`,
      };
    }
    // A present fission replaced produces no further turn (§4.2 step 6). A
    // same-name fork must not resurrect it: the present the group silenced would
    // start running again on an ordinary steer.
    if (existing !== undefined && existing.state === REPLACED) {
      return {
        name,
        steered: false,
        started: false,
        rejection: "WORKER_REPLACED",
        message: `worker ${name} was replaced by a fission group and produces no further turn`,
      };
    }
    if (existing !== undefined && existing.state !== DEAD && (existing.handle !== null || existing.admitted > existing.settled)) {
      // In flight: the verboseIntent goes into the turn that is running. Idle: it
      // starts the next turn now. A restored worker with no handle in this
      // process has no runtime to steer: its result arrives through `settled`,
      // and a verboseIntent for it is refused instead of being accepted into a queue
      // nobody will ever drain.
      if (existing.handle === null) {
        // An IDLE restored worker (its owed turn already settled) can take a
        // verboseIntent: there is no turn to steer, so it STARTS the next turn,
        // and the runtime this session holds runs it. A restored worker still
        // owing a turn has no runtime here to steer, so it is refused.
        if (existing.state === IDLE) {
          existing.admitted += 1;
          this.#record({
            type: WORKER_RECORD_TYPES.STEER,
            name,
            role: existing.role,
            delivery: "turn",
            admitted: existing.admitted,
          });
          void this.#runTurn(existing, verboseIntent);
          return { name, steered: true, started: true };
        }
        return {
          name,
          steered: false,
          started: false,
          rejection: "WORKER_RUNTIME_GONE",
          message: "the runtime behind this name was started before this plugin load and is not held here",
        };
      }
      if (existing.state === LIVE) {
        // A live worker's verboseIntent goes INTO the turn that is running (§4.1: a
        // same-name fork is a steer, not a new session). The handle's
        // `steer` is the runtime's own pending-input channel: the agent loop
        // reads it at every request boundary (`runtime.mjs`), so the text is
        // taken by the turn already in flight and no second turn is created.
        //
        // A handle that cannot take a steer is NOT reported as steered: the
        // verboseIntent waits for the worker's next turn instead. The receipt is always
        // exactly `{ name, steered, started }` — the fields that describe the
        // delivery — and never carries a rejection inside a success.
        const steered =
          typeof existing.handle.steer === "function" && existing.handle.steer(verboseIntent) === true;
        // A steered verboseIntent is a turn of this worker: the runtime's queue takes it
        // at its next request boundary, and the runtime reports it then — one
        // report per turn. It is admitted here so `owed` covers it.
        existing.admitted += 1;
        if (steered) {
          existing.steered = (existing.steered ?? 0) + 1;
          existing.carrierText ??= existing.verboseIntent || "";
        }
        if (!steered) {
          // The handle took no verboseIntent, so nothing carries it: the turn
          // starts when the running one ends, the same path a verboseIntent for
          // an idle worker takes. The receipt still says `steered` — the text did enter this
          // worker — and the turn it became is owed like any other.
          existing.pending.push(verboseIntent);
        }
        this.#record({
          type: WORKER_RECORD_TYPES.STEER,
          name,
          role: existing.role,
          // How the verboseIntent arrived: inside the running turn, or as its own turn.
          delivery: steered ? "steer" : "next",
          admitted: existing.admitted,
        });
        return { name, steered: true, started: false };
      }
      // The turn is admitted here, before it runs, so `owed` never dips to zero
      // between the settle that left the worker idle and the run that starts it.
      existing.admitted += 1;
      // The turn this verboseIntent starts is the carrier of the text itself: its
      // record names the text it was given.
      existing.verboseIntent = verboseIntent;
      existing.carrierText = null;
      // A worker this table holds no runtime for (a restored one) is STARTED by
      // this verboseIntent: the host runs it, and the table owes that turn. It is not
      // started twice — the turn was admitted just above.
      if (existing.handle === null && existing.settled >= existing.admitted) {
        existing.state = LIVE;
        void this.#runTurn(existing, verboseIntent);
      }
      this.#record({
        type: WORKER_RECORD_TYPES.STEER,
        name,
        role: existing.role,
        delivery: "turn",
        admitted: existing.admitted,
      });
      void this.#runTurn(existing, verboseIntent);
      return { name, steered: true, started: false };
    }
    const handle = await this.#create({ name, verboseIntent, role });
    if (handle?.started === false) {
      return {
        name,
        steered: false,
        started: false,
        rejection: handle.rejection ?? "HostCapabilityMissing",
        message: handle.message,
        // What was missing, by name: which sources a `MODEL_UNKNOWN` tried, or
        // which ladder a `HostCapabilityMissing` walked. Without it the model
        // reads a refusal it cannot act on.
        ...(handle.detail === undefined ? {} : { detail: handle.detail }),
      };
    }
    // The runtime started its first turn itself; that turn is the one this
    // `fork` admitted. A name that already ran turns continues their numbering:
    // a turn id is `<name>#<turn>`, and a name whose ids restarted would collide
    // with the turns already claimed, so `#complete` would drop its result.
    const base = existing?.settled ?? 0;
    const entry = this.#entryFor({ name, role, handle, lane: false, verboseIntent, base });
    // The roster reaches the disk before the runtime can report: a reload
    // between the two steps must still know this worker exists.
    this.#record({ type: WORKER_RECORD_TYPES.FORK, name, role, state: LIVE, lane: false, admitted: entry.admitted, settled: entry.settled });
    // Only NOW does the table listen: `onSettled` replays a turn that settled
    // while `#create` was awaited, and a SETTLE written before the FORK would be
    // dropped by `restoreRoster` (it ignores a SETTLE for an unknown name).
    this.#observe(entry);
    return { name, steered: false, started: true };
  }

  // A lane is a worker with its own name and the same handle shape. Its record
  // is not offered to `join`: only `fork`ed workers answer the Manager.
  //
  // `start: false` builds the lane INERT: the runtime exists, but its first
  // turn is not sent (spec §4.2 step 4 — the group record is committed before a
  // lane runs). `beginLane` sends that turn later. A host whose runtime cannot
  // be built without starting it ignores the flag; the receipt then says the
  // lane already ran, which is what the caller reads.
  async startLane({ name, verboseIntent, role, start = true }) {
    // A lane keeps the owner's identity (§4.2), which the fission port reads
    // from this table; a lane of unknown identity would run with neither face.
    if (role !== "coder" && role !== "devops") {
      return { started: false, name, rejection: "UNDECLARED_ROLE" };
    }
    // A cancelled subtree is not rebuilt by a fission step: the operator
    // stopped it, and a lane id that names a cancelled entry is refused.
    if (this.#workers.get(name)?.cancelled === true) {
      return { started: false, name, rejection: "WORKER_CANCELLED" };
    }
    const built = await this.#create({ name, verboseIntent, role, start });
    if (built?.started === false) return built;
    const entry = this.#entryFor({ name, role, handle: built.handle ?? built, lane: true, verboseIntent });
    this.#record({ type: WORKER_RECORD_TYPES.FORK, name, role, state: LIVE, lane: true, admitted: entry.admitted, settled: 0 });
    this.#observe(entry);
    return { started: true, name, entry, pending: built.pending === true };
  }

  // Send the first turn of a lane built inert. `admitted` is 1 exactly when the
  // runtime has a turn to run; a lane that started at build time has nothing to
  // send here, and answering that fact is not a failure.
  beginLane(name, verboseIntent) {
    const entry = this.#workers.get(name);
    if (entry === undefined) return { ok: false, name, rejection: "LANE_UNKNOWN" };
    if (entry.cancelled === true) return { ok: false, name, rejection: "WORKER_CANCELLED" };
    if (entry.admitted > 0 && entry.settled >= entry.admitted) {
      return { ok: false, name, rejection: "LANE_NOT_PENDING" };
    }
    if (entry.admitted === 0) entry.admitted = 1;
    void this.#runTurn(entry, verboseIntent);
    return { ok: true, name };
  }

  // The entry a handle gets. The runtime handle reports the turn it already
  // started; a handle built inert (`pending: true`) owes one turn, admitted
  // here and sent by `beginLane`.
  #entryFor({ name, role, handle, lane, verboseIntent, base = 0 }) {
    const pending = handle?.pending === true;
    // `base` is how many turns this name already settled: the counters are the
    // name's, not the runtime's, so a re-`fork`ed name keeps counting instead of
    // reusing turn ids that were already claimed.
    const entry = {
      name,
      role,
      handle,
      state: LIVE,
      view: null,
      pending: [],
      last: null,
      lane,
      admitted: base + (pending ? 0 : 1),
      settled: base,
      // The text of the turn this entry's runtime is running. A steered verboseIntent
      // rides that turn, and the carrier's own record keeps this text.
      verboseIntent:
        typeof verboseIntent === "string" && verboseIntent.length > 0
          ? verboseIntent
          : typeof handle?.verboseIntent === "string"
            ? handle.verboseIntent
            : "",
      // The text of the turn this entry's runtime is running, kept for the
      // carrier's own record once a steer rides it.
      carrierText:
        typeof verboseIntent === "string" && verboseIntent.length > 0
          ? verboseIntent
          : typeof handle?.verboseIntent === "string"
            ? handle.verboseIntent
            : null,
      restored: false,
      cancelled: false,
    };
    this.#workers.set(name, entry);
    return entry;
  }

  // Watch one entry's runtime. One settlement path for workers and lanes alike:
  // `#settle` decides what the report means (a `join` completion, or a lane's
  // delivery to its group). Split from `#entryFor` so the FORK record is on disk
  // before a buffered report can reach the journal.
  #observe(entry) {
    entry.handle?.onSettled?.((record) => this.#settle(entry, record));
    return entry;
  }

  // Watch every lane settlement (spec §4.2 steps 8 and 10). The fission port
  // registers one listener; the table does not know what a group is.
  onLaneSettled(listener) {
    if (typeof listener !== "function") return () => {};
    this.#laneListeners.push(listener);
    return () => {
      const index = this.#laneListeners.indexOf(listener);
      if (index !== -1) this.#laneListeners.splice(index, 1);
    };
  }

  // Whether this host can take a whole fission group (spec §4.2 step 1,
  // `CapacityExceeded`). `limit` is the host's measured ceiling; the room left
  // is what the ceiling has not already given to a runtime that exists.
  //
  // The group is measured, not the lanes alone: the owner is already present
  // when it fissions, so a group of n lanes needs the owner plus n more
  // runtimes under the ceiling. `starting` counts a build this call has already
  // begun but the table cannot see yet; each one is a runtime that will exist.
  capacityFor(laneCount, { limit = this.#capacityLimit, starting = 0 } = {}) {
    if (!Number.isInteger(limit) || limit <= 0) return false;
    if (!Number.isInteger(laneCount) || laneCount < 2) return false;
    const held = this.size + Math.max(0, starting);
    // The owner is one of the runtimes already counted, so it is not added
    // twice; the lanes are.
    return held + laneCount <= limit;
  }

  // One settled turn. A queued steer becomes the next turn of the same session;
  // otherwise the worker is idle and its record is waiting for `join`.
  //
  // The order matters: the turn is counted as settled BEFORE the next turn is
  // started, and the next turn is already admitted (it was counted when its
  // verboseIntent was accepted). So there is no moment at which a queued turn is
  // neither in flight nor waiting to be claimed.
  #settle(entry, record) {
    // A runtime that reports twice for one turn (or reports a turn this table
    // never admitted) must not settle a turn nobody owes: the ledger would then
    // claim work that never happened, and `join` could answer a record that is
    // not a completion.
    if (entry.settled >= entry.admitted) return false;
    entry.last = record;
    // One report answers the turn that was running AND every steered turn that
    // turn took: the runtime reads its pending-input queue at a request boundary
    // (`runtime.mjs`) and the loop answers once, with the last steer's text. So
    // one settlement clears the carrier and the steers it carried, and each of
    // them is a record `join` may hand over.
    // How many steered verboseIntents this turn actually READ. The runtime reports it
    // (`runtime.mjs` counts the queue reads); the table's own count is what it
    // HANDED over, and a turn can end without another request boundary, so the
    // two differ exactly when a verboseIntent was not delivered. Settling every queued
    // steer on the carrier's report handed `join` work nobody did.
    const queued = entry.steered ?? 0;
    const read = Number.isInteger(record?.steered) && record.steered >= 0 ? record.steered : queued;
    const carried = Math.min(queued, read, Math.max(0, entry.admitted - entry.settled - 1));
    // Only the ones that ran leave the queue: a verboseIntent the turn did not read is
    // still the runtime's pending input, and it is delivered by the next turn.
    if (carried > 0) entry.steered = queued - carried;
    const carrier = Math.min(entry.admitted, entry.settled + 1);
    entry.settled = Math.min(entry.admitted, carrier + carried);
    const turn = entry.settled;
    // The owner's view is what the turn answered. A report with no text (a
    // crash) keeps the previous view rather than blanking it.
    if (typeof record?.text === "string" && record.text.length > 0) entry.view = record.text;
    this.#record({
      type: WORKER_RECORD_TYPES.SETTLE,
      name: entry.name,
      turn,
      // The answer text is written HERE and nowhere else: a completion nobody
      // claimed needs its text after a reload, and the claim that follows
      // deletes the completion (and its text) from every later replay. A lane's
      // delivery is not collectable and its text is not replayed, so it is
      // omitted. This is the "keep only the unclaimed text" half of root §1.1
      // item 1; the journal is append-only, so a claimed answer's bytes remain
      // as immutable history but no reader loads them.
      outcome: recordOf(record),
      lane: entry.lane,
      // The carrier turn's own text, for the same reason a steer's answer is
      // written: the replay must hand `join` the same records the live table
      // would have, and the carrier is the turn the report did NOT answer with
      // its own text.
      ...(carried > 0
        ? { carrierText: entry.carrierText || entry.verboseIntent || null }
        : {}),
      // How many steered turns this report carried: the replay settles the same
      // turns the live table settled.
      steered: carried,
    });
    const next = entry.pending.shift();
    if (next !== undefined) {
      // The next turn is started NOW, and `#runTurn` marks the worker LIVE in
      // its first statement. Deferring that to a microtask would leave a `join`
      // woken by this settle to read an idle worker with nothing to claim.
      void this.#runTurn(entry, next);
      this.#wake();
      return true;
    }
    // A present fission replaced stays replaced: its turn is over, and marking
    // it idle would let a same-name fork restart it.
    if (entry.state !== REPLACED) entry.state = IDLE;
    if (entry.lane) {
      this.#deliverLane(entry, turn, record);
      return true;
    }
    // The carrier's record keeps the text of the turn it was given; each steer
    // it carried keeps the text the runtime answered with, which is what that
    // turn was working on.
    if (carried === 0) {
      this.#complete(entry, carrier, record);
      return true;
    }
    this.#complete(entry, carrier, {
      finishReason: record?.finishReason ?? "unknown",
      // The carrier's own text. A turn this load started knows it; a turn
      // restored from the journal does not (the journal carries counters, not
      // the carrier's text), and the report's text is the only text there is.
      text: entry.carrierText || entry.verboseIntent || record?.text || "",
      iterations: 0,
    });
    for (let index = 1; index <= carried; index += 1) this.#complete(entry, carrier + index, record);
    entry.carrierText = null;
    return true;
  }

  // A LANE's settled turn is its DELIVERY to its group (spec §4.2 step 8): it is
  // not a `join` completion, and the group decides what the delivery means.
  #deliverLane(entry, turn, record) {
    const settled = {
      id: `${entry.name}#${turn}`,
      name: entry.name,
      role: entry.role,
      turn,
      outcome: recordOf(record),
      lane: true,
    };
    for (const listener of this.#laneListeners) {
      try {
        listener(settled);
      } catch {
        // A listener is the group's business, not the turn's: a group that
        // throws while booking a delivery must not corrupt the table's ledger.
      }
    }
    this.#wake();
  }

  async #runTurn(entry, verboseIntent) {
    // A replaced present produces no further turn (§4.2 step 6), whatever path
    // reached here.
    if (entry.state === REPLACED) return;
    // The turn is already admitted: it was counted when the verboseIntent was accepted,
    // by `fork` (live), by `beginLane` (a lane's first turn), or by `#settle`
    // (a queued steer).
    entry.state = LIVE;
    // This turn's own text; a carrier record from a previous turn is not this
    // one's.
    entry.verboseIntent = verboseIntent;
    entry.carrierText = null;
    try {
      // A runtime with no `run` cannot start a turn. The turn stays admitted and
      // unsettled, which is the honest state of work nobody started: `join`
      // waits for it instead of being handed a record invented here.
      if (typeof entry.handle?.run !== "function") return;
      const record = await entry.handle.run(verboseIntent);
      // A host that reports each turn through `onSettled` has already moved the
      // worker on; only a run whose answer nobody reported is settled here, so
      // the view and the completion never arrive twice. A lane settles through
      // the same path: `#settle` is what reports its turn to the group
      // (§4.2 step 8) and never queues it for `join`.
      if (record === undefined || record === null || entry.state !== LIVE) return;
      this.#settle(entry, record);
    } catch {
      // The settle callback reports the failure; a rejected run is not a crash
      // of the adapter. The turn stays owed, which keeps `join` waiting rather
      // than reporting a worker that never came back.
    }
  }

  // The first worker to finish (spec §4.1). A live worker that has not finished
  // yet is something to wait for, so `join` waits for it; only a session with
  // nothing owed — no turn in flight and no record nobody claimed — says
  // honestly that there is nothing.
  //
  // Every waiter re-checks the table after each wake, so a wake shared by two
  // waiters costs one extra check and never a lost completion. Nothing spins:
  // the only thing that resolves a waiter is a completion or a release, and a
  // turn that settled without a waiter waiting is already in the queue.
  async join() {
    for (;;) {
      // Both questions are asked AFTER the last wake. The check for `null` comes
      // first because it is the final answer: a turn that started after this
      // call armed its waiter (a queued turn handed over by a settle, which
      // wakes every waiter) must be seen by the check that follows the wait,
      // not by one taken before it.
      if (this.owed() === 0) return null;
      const claimed = this.#claim();
      if (claimed !== null) return claimed;
      // The waiter is registered as a plain function, and a wake calls it
      // without consuming it: a wake that arrives while this call is still
      // deciding what to do with it is not lost, and a wake that finds nothing
      // claimable costs one re-check instead of a spin. The waiter is removed
      // when it is done with, so a table nobody waits on holds no closures.
      const arrival = this.#arrive();
      try {
        await arrival.promise;
      } finally {
        arrival.done();
      }
    }
  }

  // One wakeable. `#wake` calls the waiter and does not remove it, so a wake
  // that arrives while its caller is still between checks is not lost; the
  // caller removes it when it is done with it. A waiter is cheap: one closure
  // per `join` that had to wait, and it lives only as long as that call.
  #arrive() {
    let wake = null;
    const promise = new Promise((resolve) => {
      wake = resolve;
    });
    const waiter = () => wake();
    this.#waiters.push(waiter);
    return {
      promise,
      done: () => {
        const index = this.#waiters.indexOf(waiter);
        if (index !== -1) this.#waiters.splice(index, 1);
      },
    };
  }

  // One settled turn leaves the table for its caller. A turn already handed over
  // is never offered again, and a record for a turn nobody owed is dropped
  // instead of being answered as a completion.
  #claim() {
    // A Map keeps insertion order, so "the first settled turn" is the first
    // key. Deleting it is O(1), which the old `shift()` on an array was not.
    const first = this.#completions.values().next();
    if (first.done === true) return null;
    const completion = first.value;
    this.#completions.delete(completion.id);
    this.#claims.add(completion.id);
    // The claim is the record that the answer was handed over. A later replay
    // deletes the completion here, so its text is not part of the restored
    // state (root §1.1 item 1: the host keeps the conversation; this table keeps
    // the minimum needed to hand work over exactly once).
    this.#record({
      type: WORKER_RECORD_TYPES.CLAIM,
      name: completion.name,
      id: completion.id,
      turn: completion.turn,
      textDropped: true,
    });
    return { name: completion.name, role: completion.role, ...completion.outcome };
  }

  #complete(entry, turn, record) {
    const completion = Object.freeze({
      id: `${entry.name}#${turn}`,
      name: entry.name,
      role: entry.role,
      turn,
      outcome: recordOf(record),
      lane: false,
    });
    if (this.#claims.has(completion.id) || this.#completions.has(completion.id)) return;
    this.#completions.set(completion.id, completion);
    this.#wake();
  }

  // Wake every waiter. One wake per arrival is enough: each waiter re-checks the
  // table and re-arms if nothing is claimable.
  #wake() {
    this.#generation += 1;
    const waiters = this.#waiters;
    this.#waiters = [];
    for (const wake of waiters) wake();
  }

  markFinished(name) {
    const worker = this.#workers.get(name);
    if (worker === undefined || worker.state === DEAD) return worker ?? null;
    // A finished worker settles what it still owes: nothing of it is in flight
    // any more, and a `join` waiting on it must re-check instead of waiting for
    // a runtime that will not report.
    if (worker.settled < worker.admitted) {
      const owed = worker.settled;
      worker.settled = worker.admitted;
      worker.steered = 0;
      if (!worker.lane) {
        // Every turn it still owed is settled here, and every one of them is a
        // result: a carried turn left without a record would be work `join`
        // could never hand over.
        this.#record({
          type: WORKER_RECORD_TYPES.SETTLE,
          name,
          turn: worker.settled,
          outcome: recordOf(worker.last),
          ...(worker.settled > owed + 1
            ? { carrierText: worker.carrierText || worker.verboseIntent || null }
            : {}),
          steered: worker.settled - owed - 1,
        });
        for (let turn = owed + 1; turn <= worker.settled; turn += 1) this.#complete(worker, turn, worker.last ?? null);
      }
    }
    worker.state = IDLE;
    this.#wake();
    return worker;
  }

  // A host event that names the runtime, not the turn: the worker behind this
  // name settled. The table is the authority on whether a turn was owed, so an
  // event for a worker nobody owes is ignored rather than booked.
  settled(name, record = null) {
    const worker = this.#workers.get(name);
    if (worker === undefined) return null;
    // The host event IS the report of one owed turn. It is settled here whether
    // or not this table holds a runtime for the name: a restored worker's
    // runtime lives in the process that started it, and an event naming this
    // worker is the only channel it has. A turn already settled (the runtime's
    // own `onSettled` reported it) is not settled twice.
    this.#settle(worker, record);
    return entryView(worker);
  }

  // Results that settled and were never handed over, in settlement order.
  unclaimed() {
    return [...this.#completions.values()].map((completion) => ({
      name: completion.name,
      role: completion.role,
      turn: completion.turn,
      ...completion.outcome,
    }));
  }

  // Fission step 6: the owner's old physical present stops producing turns. A
  // runtime that is running is aborted; one that already settled has nothing to
  // silence, and the receipt says which case happened.
  //
  // `fission` runs INSIDE the owner's own turn — the program that calls it is a
  // tool call of that turn — so the abort cannot run here. `handle.abort()`
  // ends the run that is executing this very call, and a run that ends while
  // its tool call is on the stack leaves that call's promise unsettled: the
  // same in-turn deadlock `HACK.md` records for OMP. The abort is therefore
  // DEFERRED to the boundary the host itself owns, the end of that turn
  // (`atTurnEnd`, drained from `afterRun`), and the receipt says so:
  // `stoppedNow: false`, `deferredAbort: true`.
  //
  // A handle that cannot arm a boundary abort has no way to stop the present
  // after this turn, so the step refuses instead of reporting a replacement
  // that will not happen.
  replacePresent(name) {
    const worker = this.#workers.get(name);
    // Every failure carries `ok: false`: compose's `portRefused` reads that
    // field, and a refusal reported only as `replaced: false` was read as a
    // successful replacement — the group went live while the old present kept
    // producing turns.
    if (worker === undefined) return { ok: false, replaced: false, reason: "OWNER_UNKNOWN", owner: name };
    if (worker.state !== LIVE) {
      return { ok: true, replaced: true, owner: name, stoppedNow: true, deferredAbort: false, reason: "NO_LIVE_PRESENT" };
    }
    // Every verboseIntent that has not started is dropped: the owner is not producing
    // turns any more, and a queued turn would produce one.
    worker.pending.length = 0;
    const handle = worker.handle;
    try {
      if (typeof handle?.atTurnEnd === "function") {
        const armed = handle.atTurnEnd(() => {
          try {
            handle?.abort?.();
          } catch {
            // The mark and the dropped queue already stop the next turn; an
            // abort that throws here has nothing left to stop.
          }
        });
        if (armed === false) return { ok: false, replaced: false, reason: "ABORT_NOT_ARMED", owner: name };
        worker.state = REPLACED;
        return { ok: true, replaced: true, owner: name, stoppedNow: false, deferredAbort: true };
      }
      // A runtime with no turn in flight has no tool call waiting on it, so the
      // abort is safe now.
      if (typeof handle?.abort !== "function") return { ok: false, replaced: false, reason: "NO_ABORT_SURFACE", owner: name };
      handle.abort();
      worker.state = REPLACED;
      return { ok: true, replaced: true, owner: name, stoppedNow: true, deferredAbort: false };
    } catch (error) {
      return { ok: false, replaced: false, reason: "ABORT_FAILED", owner: name, message: String(error?.message ?? error) };
    }
  }

  // Fission rollback: stop every lane the group built. A lane that already
  // settled is released without an abort.
  //
  // The answer is the receipt compose documents for this step
  // (`compose/fission/port.mjs`): `{ ok, released, orphaned }`. A lane whose
  // abort threw is *orphaned* — it may still be producing turns, so it stays in
  // the table and is named in the receipt instead of being reported as stopped.
  // A lane id the table never held needs no stop and is released.
  //
  // `laneIds` may name lanes (`"g#0"`) or the lane records compose built
  // (`{ laneId }`); both are the same fact, and reading only strings released
  // nothing on a host whose port hands records.
  abortLanes(laneIds) {
    const released = [];
    const orphaned = [];
    for (const entry of laneIds) {
      const laneId = typeof entry === "string" ? entry : entry?.laneId;
      if (typeof laneId !== "string") continue;
      const worker = this.#workers.get(laneId);
      if (worker === undefined) {
        released.push(laneId);
        continue;
      }
      try {
        if (worker.state === LIVE) worker.handle?.abort?.();
      } catch (error) {
        orphaned.push({ laneId, message: String(error?.message ?? error) });
        continue;
      }
      worker.state = DEAD;
      worker.settled = Math.max(worker.settled, worker.admitted);
      this.#record({ type: WORKER_RECORD_TYPES.RELEASE, name: laneId, lane: true, reason: "LANE_ABORTED" });
      this.#workers.delete(laneId);
      released.push(laneId);
      this.#wake();
    }
    return { ok: orphaned.length === 0, released, orphaned };
  }

  // The session ends (根 §7.2, `PluginSetupContext.onDispose`): every runtime it
  // started stops producing turns. A delegated runtime lives in the host's
  // process, and a plugin loaded in the Hub shares that process across
  // sessions — a worker left running would keep spending the operator's tokens
  // for a session that no longer exists.
  //
  // The answer has the same shape as `abortLanes`, because it is the same fact:
  // `released` names what is stopped, `orphaned` names a runtime whose abort
  // threw and which may still run. A worker already dead needs no abort and is
  // still released.
  //
  // A worker that was released here settles what it still owed: an aborted turn
  // never reports, and a roster that kept waiting for it would wait forever on
  // the next load. Its result is not a completion nobody asked for — the turns
  // are settled and claimed by the release itself.
  abortAll() {
    const released = [];
    const orphaned = [];
    for (const [name, worker] of [...this.#workers]) {
      try {
        if (worker.state === LIVE) worker.handle?.abort?.();
      } catch (error) {
        orphaned.push({ name, message: String(error?.message ?? error) });
        continue;
      }
      worker.state = DEAD;
      worker.pending.length = 0;
      worker.settled = Math.max(worker.settled, worker.admitted);
      this.#record({ type: WORKER_RECORD_TYPES.RELEASE, name, lane: worker.lane, reason: "SESSION_ENDED" });
      this.#workers.delete(name);
      released.push(name);
    }
    // The queue belongs to a session that is over: nothing it holds is owed to
    // anyone now, and a reloaded table must not offer it. The claims are
    // RECORDED, not only remembered: the journal is the one source a reload
    // replays, and an in-memory claim that never reached it would resurrect the
    // completion on the next load.
    for (const completion of this.#completions.values()) {
      this.#claims.add(completion.id);
      this.#record({
        type: WORKER_RECORD_TYPES.CLAIM,
        name: completion.name,
        id: completion.id,
        turn: completion.turn,
        textDropped: true,
        reason: "SESSION_ENDED",
      });
    }
    this.#completions = new Map();
    if (released.length > 0 || this.#claims.size > 0) this.#wake();
    return { ok: orphaned.length === 0, released, orphaned };
  }

  // Root §1.1 item 2: an operator interrupt at a user-facing session reaches
  // every descendant. This is NOT `abortAll`: that one is teardown and drops the
  // queue. Here the session lives on, so
  //
  //   - a live runtime is aborted with the reason, but not disposed;
  //   - unclaimed completions STAY collectable (`join` may still hand over work
  //     that really finished before the interrupt);
  //   - every turn still owed is settled as a `cancelled` completion, so `join`
  //     reports the cancellation instead of waiting for a report that will never
  //     come;
  //   - the subtree is marked cancelled, so a later fork or steer refuses
  //     (`WORKER_CANCELLED`) instead of restarting what the operator stopped.
  //
  // Idempotent: a second interrupt finds the marks and adds nothing. A lane is
  // cancelled like a worker, but its settled turn is a group delivery, never a
  // `join` completion, so no completion is queued for it.
  cancelAll(reason = "OPERATOR_CANCELLED") {
    const cancelled = [];
    const alreadyCancelled = [];
    const orphaned = [];
    let completions = 0;
    for (const [name, worker] of [...this.#workers]) {
      if (worker.cancelled === true) {
        alreadyCancelled.push(name);
        continue;
      }
      try {
        if (worker.state === LIVE || worker.state === IDLE) worker.handle?.abort?.(reason);
      } catch (error) {
        // The mark and the settled turns still stop this table from waiting on
        // it; the receipt names the runtime that may keep running.
        orphaned.push({ name, message: String(error?.message ?? error) });
      }
      worker.pending.length = 0;
      worker.cancelled = true;
      const owed = Math.max(0, worker.admitted - worker.settled);
      const turn = worker.settled + owed;
      worker.settled = turn;
      worker.steered = 0;
      worker.state = DEAD;
      this.#record({
        type: WORKER_RECORD_TYPES.CANCEL,
        name,
        reason,
        turn,
        lane: worker.lane,
      });
      if (!worker.lane) {
        for (let settledTurn = turn - owed + 1; settledTurn <= turn; settledTurn += 1) {
          this.#complete(worker, settledTurn, { finishReason: "cancelled", text: "" });
          completions += 1;
        }
      }
      cancelled.push(name);
    }
    if (cancelled.length > 0) this.#wake();
    return { ok: orphaned.length === 0, cancelled, alreadyCancelled, orphaned, completions };
  }
}
