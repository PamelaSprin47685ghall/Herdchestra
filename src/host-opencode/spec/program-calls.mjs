// Program names (§3.3 "名字占用", §3.4).
//
// A program's name is the host call id: one physical call, one name. The table
// holds every live program — running, waiting or parked — so `unpark` can find
// a parked one and a reused name is refused instead of silently replaced.
//
// A parked program keeps its source for diagnostics; its body is never re-run —
// the suspended promise continues it (program-execution.mjs).
//
// When a program reaches a terminal state its name is retired; the core
// registry keeps the replay records, so a replayed call id still answers with
// the original receipt.

import { CoreError, CODES } from "../../core/errors.mjs";
import { ProgramState, STATES } from "../../core/program.mjs";
import { programView } from "../../core/receipt.mjs";
import { stableJson } from "../../core/util.mjs";
import { ParkTable } from "./park-table.mjs";

export class ProgramCalls {
  #registry;
  #parks;
  #sources;
  #stops;
  #calls;

  // `calls` is this session's durable record of used call ids (`call-record.mjs`):
  // `{ ok, ids }` when it could be read, `{ ok: false, reason }` when it could
  // not. A record that cannot be read makes every call id of this session
  // ambiguous, and the gate refuses rather than running a call that may have
  // already happened.
  constructor({ registry, parks = new ParkTable(), calls = null } = {}) {
    this.#registry = registry;
    this.#parks = parks;
    this.#sources = new Map();
    // Park stops of suspended programs: what the resumer waits on.
    this.#stops = new Map();
    this.#calls = calls ?? { ok: true, ids: new Set() };
  }

  // Bring a call id used in an EARLIER process back into this process's tables,
  // so a replay behaves exactly as it does in the process that ran the call:
  // same payload → the original receipt; different payload → the mismatch
  // refusal. The facts come from the durable record (`call-record.mjs`), which
  // keeps the payload and the receipt beside the id.
  //
  //   { used: false }                        this process has never seen the id
  //   { used: true, restored: true }         rebuilt from the record
  //   { used: true, receipt: null }          recorded, but the call never
  //                                          finished: nothing to replay
  //   { used: true, unreadable }             the record cannot be trusted
  //   { used: false, unwritable }            the id could not be recorded, so
  //                                          the call must not run
  restoreFrom({ id, recordCall = null } = {}) {
    if (typeof id !== "string" || id.length === 0) return { used: false };
    if (this.#registry.used(id)) return { used: false };
    if (this.#calls.ok !== true) return { used: true, unreadable: this.#calls.reason ?? "CALL_RECORD_UNREADABLE" };
    if (!this.#calls.ids.has(id)) {
      // Recorded before the call runs, so a crash mid-call still leaves the
      // fact. A write that fails is reported: a call the adapter cannot
      // remember must not run, or a restart would perform it twice.
      if (typeof recordCall === "function") {
        const noted = recordCall(id);
        if (noted?.ok !== true) {
          return { used: false, unwritable: noted?.reason ?? "CALL_RECORD_UNWRITABLE", detail: noted?.detail ?? null };
        }
        this.#calls.ids.add(id);
      }
      return { used: false };
    }
    const stored = this.#calls.receipts.get(id) ?? null;
    if (stored === null || typeof stored.state !== "string") {
      // The id was spent but no finished receipt was kept: the process died
      // mid-call, or the record predates the receipt. Nothing can be replayed,
      // and re-running is what the rule forbids.
      return { used: true, receipt: null };
    }
    const program = ProgramState.restored({
      name: id,
      state: stored.state,
      result: stored.value ?? null,
      payload: this.#calls.payloads.get(id) ?? null,
    });
    this.#registry.restore(program);
    // The source is part of the receipt a replay answers with, so it is kept
    // beside the program: a restored call that reported a different source
    // would not be the original receipt.
    this.#sources.set(id, stored.source ?? null);
    return { used: true, restored: true, receipt: stored };
  }

  // Record that a program stopped at a park. The resumer awaits this promise;
  // it settles with the program's next stop.
  stop(name) {
    let resolve;
    const promise = new Promise((settle) => {
      resolve = settle;
    });
    this.#stops.set(name, { promise, resolve });
    return promise;
  }

  hasStop(name) {
    return this.#stops.has(name);
  }

  // Wake the resumer of a program with its next stop, or null when nothing
  // waits on it.
  wake(name, next) {
    const entry = this.#stops.get(name);
    if (entry === undefined) return false;
    this.#stops.delete(name);
    entry.resolve(next);
    return true;
  }

  // Await the next stop of a program. Resolves to null when the program is
  // already finished, so a resumer never hangs.
  async waitStop(name) {
    const program = this.program(name);
    if (program === null || program.terminal) return null;
    const entry = this.#stops.get(name);
    if (entry === undefined) return null;
    return await entry.promise;
  }

  get parks() {
    return this.#parks;
  }

  get size() {
    return this.#parks.size;
  }

  // Start a program under this name, or return null when the name is live.
  // `deadline` is the request's core `Deadline`, fixed at admission; the
  // program owns it from then on (§3.3).
  open(name, source = null, { deadline = null, capabilities = [] } = {}) {
    if (this.#registry.live(name) !== null) return null;
    // Core §3.3: the capability set is a fact of the program, not a parameter of
    // `unpark`. Two programs of one session carry the same set, so a `Plan` can
    // resume the `ask` park it made — and no other session can.
    const program = this.#registry.begin(name, { deadline, capabilities });
    this.#sources.set(name, source);
    return program;
  }

  source(name) {
    return this.#sources.get(name) ?? null;
  }

  program(name) {
    return this.#registry.get(name);
  }

  // The park site a program is suspended on, or null. `unpark` is answered by
  // the resumer, which needs the site to tell an `ask` park from any other.
  siteOf(name) {
    return this.program(name)?.site ?? null;
  }

  // The current receipt of a live program, shaped like a park receipt.
  viewOf(name) {
    const program = this.program(name);
    if (program === null) return null;
    return programView(program, { source: this.source(name) });
  }

  // Free a terminal program's name. A parked program keeps its entry.
  closeIfTerminal(name) {
    const program = this.program(name);
    if (program === null || !program.terminal) return null;
    this.#parks.close(name);
    this.#sources.delete(name);
    this.#registry.retire(name);
    return program;
  }

  // Names still parked, in lexical order: the Pair Hint lists them (§6.1).
  // `ProgramRegistry` defines `live(name)` and then `live()` again, so the
  // zero-argument form is unreliable; the source table is the honest list.
  parked() {
    const names = [];
    for (const name of this.#sources.keys()) {
      if (this.program(name)?.state === STATES.PARKED) names.push(name);
    }
    return names.sort();
  }

  // Every name this session still holds a program under, parked or not. A
  // later call observes these so `unpark` can reach them (§3.3): the
  // observation ledger belongs to one request, and the target must be one this
  // session's live work already contains.
  liveNames() {
    return [...this.#sources.keys()];
  }

  // Whether a physical call already finished under this name. §2.4 第 6 步:
  // the SAME callId replayed answers with the original receipt and performs
  // nothing twice. A live program answers `false` here — it is running, not
  // replayed — and `open` refuses that case instead.
  retired(name) {
    const program = this.#registry.get(name);
    return program !== null && program.terminal;
  }

  // The receipt a replay answers with: the finished program's own view, source
  // included. Nothing here performs the call again — the state, the counters
  // and the value are the first run's.
  replayOf(name) {
    const program = this.#registry.get(name);
    if (program === null) return null;
    return programView(program, { source: this.source(name) });
  }

  // What a replay answers with: the ORIGINAL receipt, value and source
  // included. A program this process ran holds its result; a program restored
  // from the durable record holds the receipt the record kept, which is the
  // same answer after a restart. Re-running is what the rule forbids, so the
  // answer must be the first run's, not a fresh view that has no value.
  replayAnswer(name, { source = null } = {}) {
    const program = this.#registry.get(name);
    if (program === null) return null;
    const result = program.result;
    const value = result !== null && typeof result === "object" && "value" in result ? result.value : result;
    const error = result !== null && typeof result === "object" && "error" in result ? result.error : null;
    return {
      ...programView(program, { source: this.source(name) ?? source }),
      value: value ?? null,
      error: error ?? null,
      replayed: true,
    };
  }

  // Whether a replay of this call id carries the payload the first call did
  // (§2.4 第 6 步). `payload` is the whole tool input, compared through core's
  // own stable encoding so key order cannot fake a difference. A program that
  // recorded none answers `false`: an unrecorded payload cannot be shown equal,
  // and guessing equality would let a changed call through.
  matchesPayload(name, payload) {
    const program = this.#registry.get(name);
    if (program === null) return false;
    const recorded = program.payload;
    if (typeof recorded !== "string") return false;
    return recorded === stableJson(payload);
  }

}
