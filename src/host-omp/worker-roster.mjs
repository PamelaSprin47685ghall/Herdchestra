// The worker roster of ONE session, durable across a reload (§4.1, §7.3).
//
// A worker here is a real OMP session this adapter created through the SDK
// (`lane.mjs`). It outlives the adapter that started it: a plugin reload, a
// process restart, or a second extension instance rebinds the factory and the
// in-memory `LaneTable` is gone while the worker sessions keep running. Measured
// on the OpenCode sibling (2026-11-01, session `ses_e6ab649c8ffea0B023Dy3Aj2P0`):
// four workers were forked, two were joined, the plugin reloaded, and every
// later `join` answered "nothing to collect" in 1 ms while two workers were
// still running. The same shape would make an OMP `join` answer
// `{ ok: false, record: null }` for work that exists.
//
// So the facts `join` decides on are written to the session's own journal and
// replayed into a table that starts after a reload:
//
//   worker-fork      a worker was registered for this session
//   worker-steer     the brief went to a live worker of the same name
//   worker-settle    the worker's turn ended (finished, or failed)
//   worker-claim     a `join` handed the result to the caller
//   worker-release   a finished worker started a new turn under the same name
//   worker-restore   a reload found the journal: it says how many were rebuilt
//
// ## What survives, and what does not
//
// A finished turn's TEXT lives in the worker's own transcript, which this
// adapter does not read for another session. The durable record therefore
// carries the identity and the outcome, and the restored entry says
// `restored: true` so a caller can tell a result recovered from the journal
// from one it watched finish. Nothing is invented: `text` is `null` on a
// restored result, never a guess.
//
// ## Reading is a replay, not a snapshot
//
// The journal is append-only and may be torn (a crash mid-append). A trailing
// partial line is dropped, and every earlier complete record still answers.
// One record means one fact about one NAME at one moment; the replay folds them
// in order, so a settle followed by a steer followed by a claim is exactly the
// worker's story.

import fs from "node:fs";
import path from "node:path";

import { readJsonLines } from "./files.mjs";
import { stateDir, safeSessionName } from "./host-facts.mjs";

export const WORKER_RECORD_TYPES = Object.freeze({
  FORK: "worker-fork",
  STEER: "worker-steer",
  SETTLE: "worker-settle",
  CLAIM: "worker-claim",
  RELEASE: "worker-release",
  RESTORE: "worker-restore",
});

const RECORD_TYPES = Object.freeze(Object.values(WORKER_RECORD_TYPES));
const RECORD_VERSION = 1;
const RUNNING = "running";
const FINISHED = "finished";

const isName = (value) => typeof value === "string" && value.length > 0;

// Append one record and make it durable BEFORE the caller is told it landed.
//
// `files.mjs` `appendJsonLine` is the plain append, and a plain append is still
// in the kernel's page cache when it returns: a reload that reads the file
// immediately (the measured failure) then sees a roster that does not hold the
// worker it just forked. So this writes through the file descriptor, fsyncs,
// and closes — the same shape `writeJson` uses for its atomic replace.
const appendDurable = (target, record) => {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const fd = fs.openSync(target, "a", 0o644);
  try {
    fs.writeFileSync(fd, `${JSON.stringify(record)}\n`, "utf8");
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
};

// The file one session's roster lives in. The session's own name, sanitised the
// same way every other `.Herdchestra` file is (`host-facts.mjs`), so one session
// keeps one directory and a reader can find it without a lookup table.
export const workerRosterFile = (root, sessionId) =>
  `${stateDir(root)}/${safeSessionName(sessionId)}.workers.jsonl`;

// Whether a journal entry is one of ours.
export const isWorkerRecord = (record) =>
  typeof record?.type === "string" && RECORD_TYPES.includes(record.type);

// A finished worker's result, in the shape `join` hands over. The text is the
// turn's own answer when this process watched it end, and `null` when the
// result was recovered from the journal: the transcript that holds it is the
// worker's, and this adapter does not read it for somebody else.
export const workerResult = ({ name, agentId = null, role = null, text = null, startedAt = null, error = null, restored = false }) =>
  Object.freeze({
    ok: true,
    name,
    agentId: agentId ?? name,
    role: role ?? null,
    text: typeof text === "string" ? text : null,
    startedAt: startedAt ?? null,
    ...(error === null || error === undefined ? {} : { error }),
    ...(restored === true ? { restored: true } : {}),
  });

// Rebuild the roster a journal's records describe.
//
// The answer is the shape `WorkerRoster.restore` takes:
//   entries    every name the journal knows, with its current state
//   unclaimed  finished results nobody joined, in the order they finished
//
// A record that does not match the entry it names is ignored rather than
// applied: a settle for a session the name no longer holds describes work that
// has already been superseded.
export const replayRoster = (records) => {
  const entries = new Map();
  // The names whose result a claim took, in the order the claims were replayed.
  const claimed = [];
  const order = [];
  // One turn's result, queued in settle order. Results are per TURN: a later
  // settle of the same name queues BEHIND an earlier one that nobody claimed,
  // it never replaces it (a finished turn's report is work the Manager has not
  // seen, and §4.1 hands over whoever came back).
  const pushResult = (entry) => {
    order.push(
      workerResult({
        name: entry.name,
        agentId: entry.agentId,
        role: entry.role,
        text: entry.text,
        startedAt: entry.startedAt,
        error: entry.error,
        restored: true,
      }),
    );
  };
  for (const record of Array.isArray(records) ? records : []) {
    if (!isWorkerRecord(record)) continue;
    const name = record.name;
    if (!isName(name)) continue;
    const entry = entries.get(name) ?? null;
    if (record.type === WORKER_RECORD_TYPES.FORK) {
      // A fork of a name that is not LIVE starts a new worker (§4.1). It is not
      // a steer, so it withdraws nothing: a turn that already returned and that
      // no `join` claimed is still owed, because `join` reports the first
      // worker to come back and this one has not been collected yet. Only a
      // STEER record supersedes an earlier turn of the same name.
      const wasLive = entry !== null && entry.state === RUNNING;
      entries.set(name, {
        name,
        agentId: isName(record.agentId) ? record.agentId : name,
        role: typeof record.role === "string" ? record.role : null,
        state: RUNNING,
        startedAt: Number.isFinite(record.startedAt) ? record.startedAt : null,
        text: null,
        error: null,
      });
      // A fork over a name that was already live is a steer in effect. It starts
      // a turn; it withdraws nothing, because results are per turn and are never
      // withdrawn.
      continue;
    }
    if (record.type === WORKER_RECORD_TYPES.STEER) {
      // A steer starts a new turn on the same worker. It withdraws nothing: an
      // earlier turn whose report nobody joined is still owed and stays queued.
      if (entry === null || entry.state !== FINISHED) continue;
      entry.state = RUNNING;
      entry.error = null;
      continue;
    }
    if (record.type === WORKER_RECORD_TYPES.SETTLE) {
      if (entry === null || entry.state !== RUNNING) continue;
      entry.state = FINISHED;
      // The turn's own answer, recorded when it ended. A reload then owes the
      // same words, not only the name.
      entry.text = typeof record.text === "string" ? record.text : null;
      entry.error = record.error ?? null;
      // Each settle is one queued result, in settle order. An earlier turn of
      // the same name that nobody claimed stays queued ahead of it.
      pushResult(entry);
      continue;
    }
    if (record.type === WORKER_RECORD_TYPES.CLAIM) {
      // A claim takes the OLDEST queued result of that name, which is the one
      // `join` handed over — whatever the name's state is NOW. `fork, settle t1,
      // steer, claim` is legal: the steer makes the name running while t1's
      // result is still queued, and the claim that follows took t1. Replaying it
      // by state instead would drop that claim and hand t1 over twice.
      //
      // It is ignored only when no result of that name is queued: then it took
      // nothing, and there is nothing to remove.
      if (entry === null) continue;
      const index = order.findIndex((result) => result.name === name);
      if (index === -1) continue;
      order.splice(index, 1);
      claimed.push(name);
      continue;
    }
    if (record.type === WORKER_RECORD_TYPES.RELEASE) {
      // The name now points at a session that has its own fork record; dropping
      // the old entry keeps a stale worker from answering for the name.
      if (entry !== null) entries.delete(name);
      const index = order.findIndex((result) => result.name === name);
      if (index !== -1) order.splice(index, 1);
    }
  }
  return {
    entries: [...entries.values()],
    // Every queued turn nobody claimed, in the order the turns ended.
    unclaimed: order,
    // A claim is one result, not a fact about the name: the list is kept for
    // callers that read it, but a rebuild no longer suppresses a name's later
    // turns from it.
    claimed,
  };
};

// The durable roster. One instance per session; a second instance over the same
// file is what a reload looks like, and `restore` is how it learns what the
// previous one wrote.
//
// `write(entry)` appends one record and answers whether it landed. A roster
// that did not reach the disk is still usable in this process — the lane table
// above it holds the same facts — but `lastWriteFailure` says so, and the next
// `join` reports it instead of silently forgetting.
export class WorkerRoster {
  #file;
  #write;
  #restored;
  #lastWriteFailure = null;

  // `file` is the journal path; `write` performs one append. A roster built
  // without a `file` (a test, or a session with no state directory) is the
  // in-memory roster of one process: it still folds its own records, so the
  // replay has exactly one meaning.
  constructor({ file = null, write = null } = {}) {
    this.#file = typeof file === "string" && file.length > 0 ? file : null;
    this.#write =
      typeof write === "function"
        ? write
        : this.#file === null
          ? null
          : (entry) => appendDurable(this.#file, { v: RECORD_VERSION, ...entry });
    this.#restored = this.#read();
  }

  get file() {
    return this.#file;
  }

  // Why the last append did not land, or null.
  get lastWriteFailure() {
    return this.#lastWriteFailure;
  }

  // The journal as this roster sees it, including everything it wrote itself.
  // A read that fails is reported as no records: the caller's roster is then
  // whatever it has in memory, which is the direction that loses a result
  // rather than inventing one.
  #read() {
    if (this.#file === null) return [];
    try {
      return readJsonLines(this.#file, (decoded) => decoded).filter(isWorkerRecord);
    } catch {
      return [];
    }
  }

  // The roster the journal described when this instance was built.
  get restored() {
    return replayRoster(this.#restored);
  }

  // Append one record. A failed append is remembered, never thrown: a worker
  // that started must not be torn down because a disk write failed, and the
  // failure is reported by the caller that reads `lastWriteFailure`.
  record(entry) {
    if (!isWorkerRecord(entry)) return false;
    if (this.#write === null) {
      this.#restored.push(entry);
      return true;
    }
    try {
      this.#write(entry);
      this.#restored.push(entry);
      this.#lastWriteFailure = null;
      return true;
    } catch (error) {
      this.#lastWriteFailure = {
        code: typeof error?.code === "string" ? error.code : "EIO",
        detail: String(error?.message ?? error),
        entry,
      };
      return false;
    }
  }
}
