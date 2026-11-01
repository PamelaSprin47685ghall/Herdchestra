// Program lifecycle state shared by every surface (spec §3.3).
//
// States: running -> waiting -> parked -> running; terminal: completed, failed.
// `draining` and `settling` are transitions, not stored states: a program stops
// accepting new expressions while it drains its own pending operations.
//
// The registry owns facts only: state, parked site, replay records, counters and
// the run's deadline. It never runs user code and never talks to a host.

import { CoreError, CODES, invalidArgument } from "./errors.mjs";
import { stableJson } from "./util.mjs";

// The site reason of an expired run, as `core/receipt.mjs` spells it. Written
// here rather than imported because `receipt.mjs` reads `STATES` from this
// module: the two files form one cycle, and the one value this module needs is
// a word, not a table.
const EXPIRED_REASON = "explicit";

export const STATES = Object.freeze({
  RUNNING: "running",
  WAITING: "waiting",
  PARKED: "parked",
  COMPLETED: "completed",
  FAILED: "failed",
});

export const TERMINAL_STATES = Object.freeze([STATES.COMPLETED, STATES.FAILED]);

export const isTerminal = (state) => TERMINAL_STATES.includes(state);

const assertState = (state) => {
  if (!Object.values(STATES).includes(state)) {
    throw invalidArgument(`unknown program state: ${state}`);
  }
  return state;
};

export class ProgramState {
  #name;
  #state;
  #site;
  #operations;
  #replays;
  #result;
  #parkCount;
  #deadline;
  #waiters;
  #nextOperation;
  #open;
  #capabilities;
  #payload;
  #expired;

  // `capabilities` is the set a controller must hold to resume this program
  // (spec §3.3). It is a fact about the program, not a parameter of the
  // `unpark` call: a controller that names the required set itself could name
  // the empty one and pass every check.
  constructor(name, { deadline = null, capabilities = [] } = {}) {
    if (typeof name !== "string" || name.length === 0) {
      throw invalidArgument("program name must be a non-empty string");
    }
    if (!Array.isArray(capabilities) || !capabilities.every((item) => typeof item === "string")) {
      throw invalidArgument("capabilities must be an array of strings");
    }
    this.#name = name;
    this.#state = STATES.RUNNING;
    this.#site = null;
    this.#operations = 0;
    this.#replays = new Map();
    this.#result = null;
    this.#parkCount = 0;
    this.#deadline = deadline;
    this.#waiters = new Set();
    // Operation ids start at 1; id 0 is "no operation".
    this.#nextOperation = 0;
    this.#open = new Set();
    this.#capabilities = Object.freeze([...capabilities]);
    // The physical call's payload, as the adapter saw it (root §2.4 第 6 步:
    // 同一 callId 换用不同载荷必须拒绝). A program is one physical call, so
    // this is stored once and compared on every replay of that call id. It is
    // the adapter's business what belongs in it — for this host the whole tool
    // input, not only the program source.
    this.#payload = null;
    // Set once the run's deadline ran out and the program suspended on it. An
    // expired run accepts no new expression, and it is resumed by the
    // controller only — never by a `call` that was already waiting.
    this.#expired = false;
  }

  get name() {
    return this.#name;
  }

  // A finished program rebuilt from persisted facts, for `ProgramRegistry
  // .restore`. Only the facts a replay needs are set: name, terminal state,
  // result and payload. Nothing else is claimed — no deadline, no park site, no
  // open operations — because those describe a run that is over.
  static restored({ name, state, result = null, payload = null }) {
    const program = new ProgramState(name);
    assertState(state);
    if (!isTerminal(state)) throw invalidArgument("a restored program must be finished");
    program.#state = state;
    program.#result = result;
    program.#payload = payload === null ? null : stableJson(payload);
    return program;
  }

  // The payload of the physical call that started this program, or null when
  // the caller recorded none.
  get payload() {
    return this.#payload;
  }

  // Record the call's payload once. A second record that disagrees is refused:
  // two different payloads under one call id is the mismatch the spec forbids,
  // and keeping the first would hide it.
  recordPayload(payload) {
    if (this.#payload === null) {
      this.#payload = stableJson(payload);
      return this;
    }
    if (this.#payload !== stableJson(payload)) {
      throw new CoreError(
        CODES.CALL_REPLAY_MISMATCH,
        `callId ${this.#name} was used with a different payload`,
        { callId: this.#name },
      );
    }
    return this;
  }

  get state() {
    return this.#state;
  }

  get site() {
    return this.#site;
  }

  get pendingOperations() {
    return this.#operations;
  }

  get result() {
    return this.#result;
  }

  get terminal() {
    return isTerminal(this.#state);
  }

  get parkCount() {
    return this.#parkCount;
  }

  // Whether this run suspended because its deadline ran out (spec §3.3: 一旦
  // 到期). Distinct from `state === PARKED` on purpose: a park is a site a
  // controller resumes with a value, and an expiry is the request's own time
  // ending, which no controller resumes.
  get expired() {
    return this.#expired;
  }

  get deadline() {
    return this.#deadline;
  }

  // The capability set a controller must hold to resume this program.
  get capabilities() {
    return this.#capabilities;
  }

  // A run may still start an expression while it is running or waiting. A
  // parked program is suspended, a terminal one is closed, and a DRAINING one
  // takes none: spec §3.3 makes draining a transition in which "程序不再接受
  // 新的表达式推进". Without this gate a second expression is admitted while
  // the first still waits, and it overtakes that earlier work.
  //
  // Draining means "an expression is waiting for earlier operations to settle",
  // not "operations are open": a program's own open operation is why it is
  // allowed to continue, so counting operations here would deadlock it.
  //
  // An expired run takes no new expression: its deadline is spent, so anything
  // it started now would be cut off before it could be reported.
  get acceptsExpressions() {
    return !this.isTerminal() && this.#state !== STATES.PARKED && !this.draining && !this.#expired;
  }

  // True while an expression waits for the operations that predate it.
  get draining() {
    return this.#waiters.size > 0;
  }

  isTerminal() {
    return isTerminal(this.#state);
  }

  // Give this run a deadline. Admission does it through the constructor; a
  // resume does it with the controlling request's fresh deadline.
  attachDeadline(deadline) {
    if (deadline === null) throw invalidArgument("a program deadline cannot be null");
    this.#deadline = deadline;
    return deadline;
  }

  // The run's expiry signal, or null when it has no deadline. A waiter races
  // this: expiry is the one event that reaches a program blocked on anything.
  get expirySignal() {
    return this.#deadline === null ? null : this.#deadline.signal;
  }

  // Suspend this run because its deadline ran out (spec §3.3: 一旦到期，控制者与
  // 仍在活动的目标程序一同转入 `parked`). The site is the expiry itself —
  // `reason: "explicit"`, no payload — because no model expression parked it and
  // there is no question to answer.
  //
  // Not `park()`: a park cancels the deadline, and cancelling the deadline here
  // would cancel the signal the expiry just raised. The run keeps its spent
  // deadline; a resume replaces it with the controller's, exactly as a park's
  // resume does.
  //
  // A terminal run and a run that is already suspended accept nothing: the
  // caller reads `false` and reports the state it found.
  expire() {
    if (this.isTerminal()) return false;
    if (this.#state === STATES.PARKED) return false;
    this.#site = { reason: EXPIRED_REASON, payload: null };
    this.#state = STATES.PARKED;
    this.#expired = true;
    return true;
  }

  // What is left of the run's deadline; null when there is none.
  remainingWaitMs() {
    return this.#deadline === null ? null : this.#deadline.remainingMs();
  }

  // Move to a state. A finished run owns its terminal state, so a transition out
  // of it is a no-op that returns the current state: an operation that settled
  // late may still run its `finally`, and that must not reopen a closed run.
  // `parked` and `waiting` are reachable both ways, as they always were; hosts
  // that move a resumed program back to `running` keep working.
  moveTo(next) {
    assertState(next);
    if (this.isTerminal() || this.#state === next) return this.#state;
    this.#state = next;
    if (next !== STATES.PARKED) this.#site = null;
    return next;
  }

  // Park the program. A program with an unsettled site cannot park twice, and a
  // finished one accepts no new park. Parking cancels this run's deadline.
  park(site) {
    if (this.isTerminal()) {
      throw invalidArgument(`program ${this.#name} is ${this.#state}`);
    }
    if (this.#site !== null) {
      throw invalidArgument(`program ${this.#name} already has a parked site`);
    }
    this.#site = site;
    this.#state = STATES.PARKED;
    this.#parkCount += 1;
    this.#expired = false;
    if (this.#deadline !== null) this.#deadline.cancel();
    return site;
  }

  // Consume the parked site exactly once; a second call is STALE_CONTROL.
  // `deadline` is the controlling request's deadline for the resumed run.
  takeSite({ deadline = null } = {}) {
    if (this.#state !== STATES.PARKED || this.#site === null) {
      throw new CoreError(CODES.STALE_CONTROL, `program ${this.#name} is not parked`);
    }
    const site = this.#site;
    this.#site = null;
    this.#state = STATES.RUNNING;
    this.#expired = false;
    if (deadline !== null) this.#deadline = deadline;
    return site;
  }

  // Register one operation and return its id. The id is the operation's
  // identity: `drainedPromise(id)` waits for the operations whose ids are
  // smaller, which is exactly "everything this program started earlier". A
  // caller can therefore never skip an earlier operation by counting, and can
  // never wait for one that has not started yet.
  beginOperation() {
    this.#nextOperation += 1;
    this.#operations += 1;
    this.#open.add(this.#nextOperation);
    return this.#nextOperation;
  }

  // Settle one operation, named by the id `beginOperation()` returned.
  //
  // There is no "settle the newest open operation" fallback: operations finish
  // in whatever order the host completes them, so "newest" is a guess, and a
  // guess that settles the wrong one leaves a predecessor open forever (every
  // later drain then waits for it) or settles a later operation early. The id
  // is the only correct answer, so it is required.
  endOperation(id) {
    if (this.#operations === 0) {
      throw invalidArgument(`program ${this.#name} has no pending operation`);
    }
    if (!Number.isInteger(id) || id <= 0 || id > this.#nextOperation) {
      throw invalidArgument(`program ${this.#name} never started operation ${id}`);
    }
    if (!this.#open.delete(id)) {
      throw invalidArgument(`operation ${id} of program ${this.#name} is already settled`);
    }
    this.#operations -= 1;
    this.#notifyDrained();
    return this.#operations;
  }

  get drained() {
    return this.#operations === 0;
  }

  // Resolve once every operation the program started BEFORE `before` has
  // settled. `before` is an operation id from `beginOperation()`, so the wait
  // names actual predecessors instead of counting them:
  //
  // - the caller's own operation (id `before`) is not waited for, so an
  //   expression that runs while another operation is open cannot deadlock on
  //   a decrement only it could perform;
  // - no earlier operation is skipped, whatever the count is at the moment;
  // - a later caller's operation (id greater than `before`) is not waited for,
  //   so "wait for the earlier ones" never becomes "wait for everyone".
  //
  // `before` defaults to the id that would come next, i.e. "every operation
  // this program has started".
  drainedPromise(before = this.#nextOperation + 1) {
    if (!Number.isInteger(before) || before < 1) {
      throw invalidArgument("drain boundary must be an operation id");
    }
    if (!this.#hasEarlier(before)) return Promise.resolve();
    return new Promise((settle) => this.#waiters.add({ before, settle }));
  }

  #hasEarlier(before) {
    for (const open of this.#open) if (open < before) return true;
    return false;
  }

  #notifyDrained() {
    for (const waiter of [...this.#waiters]) {
      if (this.#hasEarlier(waiter.before)) continue;
      this.#waiters.delete(waiter);
      waiter.settle();
    }
  }

  // Replay record per physical callId. The same callId with the same payload
  // returns the recorded receipt; a different payload is a mismatch.
  replay(callId, payload, compute) {
    const key = String(callId);
    const seen = this.#replays.get(key);
    if (seen === undefined) {
      const receipt = compute();
      this.#replays.set(key, { payload: stableJson(payload), receipt });
      return { receipt, replayed: false };
    }
    if (seen.payload !== stableJson(payload)) {
      throw new CoreError(
        CODES.CALL_REPLAY_MISMATCH,
        `callId ${key} was used with a different payload`,
        { callId: key },
      );
    }
    return { receipt: seen.receipt, replayed: true };
  }

  hasReplay(callId) {
    return this.#replays.has(String(callId));
  }

  finish(state, result) {
    if (!isTerminal(state)) {
      throw invalidArgument("a program finishes as completed or failed");
    }
    if (this.isTerminal()) {
      throw invalidArgument(`program ${this.#name} is ${this.#state}`);
    }
    this.#state = state;
    this.#site = null;
    this.#result = result;
    if (this.#deadline !== null) this.#deadline.cancel();
    // A finished run accepts no new expression, so nothing it is waiting for can
    // still arrive: every drain waiter is released rather than left pending on
    // a count that will never reach its target.
    this.#releaseWaiters();
    return result;
  }

  #releaseWaiters() {
    for (const waiter of [...this.#waiters]) {
      this.#waiters.delete(waiter);
      waiter.settle();
    }
  }
}

// The name table. A name is free once its program is terminal; the retired
// program keeps its replay records, so replaying an old callId answers with the
// old receipt instead of starting a second run.
export class ProgramRegistry {
  #programs;
  #retired;

  constructor() {
    this.#programs = new Map();
    this.#retired = new Map();
  }

  // Start a program under `name`. A live name is NAME_TAKEN, always: replay is
  // a separate step (`record`), so a caller never confuses the two.
  begin(name, { deadline = null, capabilities = [] } = {}) {
    if (this.#programs.has(name)) {
      throw new CoreError(CODES.NAME_TAKEN, `name ${name} is taken`, { name });
    }
    const program = new ProgramState(name, { deadline, capabilities });
    this.#programs.set(name, program);
    return program;
  }

  // Replay a physical call against an already started program.
  record(program, callId, payload, compute) {
    return program.replay(callId, payload, compute);
  }

  // Replay a callId of a retired program. Throws PROGRAM_UNKNOWN when neither a
  // live nor a retired program ever saw that callId.
  replayRetired(callId, payload) {
    const key = String(callId);
    for (const program of this.#retired.values()) {
      if (program.hasReplay(key)) return program.replay(key, payload, () => null);
    }
    throw new CoreError(CODES.PROGRAM_UNKNOWN, `callId ${key} is unknown`, { callId: key });
  }

  get(name) {
    return this.#programs.get(name) ?? this.#retired.get(name) ?? null;
  }

  live(name) {
    return this.#programs.get(name) ?? null;
  }

  require(name) {
    const program = this.get(name);
    if (program === null) {
      throw new CoreError(CODES.PROGRAM_UNKNOWN, `program ${name} is unknown`, { name });
    }
    return program;
  }

  // Move a terminal program out of the live table so its name can be reused.
  retire(name) {
    const program = this.#programs.get(name);
    if (program === undefined || !program.terminal) return null;
    this.#programs.delete(name);
    this.#retired.set(name, program);
    return program;
  }

  // Whether this call id was ever used in this table, live or retired. A name
  // that is live is "used" too: the caller distinguishes the two with `live`.
  // The answer is in memory only — it dies with the process — which is exactly
  // why the adapter keeps its own durable copy of the same fact.
  used(name) {
    return this.#programs.has(name) || this.#retired.has(name);
  }

  // Put a finished program back into the retired table, so a replay of its call
  // id answers from it. Used when a restarted process restores the facts it
  // persisted: the program's state, result and payload are all known, and
  // `replay` compares the payload exactly as it would in the same process.
  restore(program) {
    if (program === null || typeof program !== "object" || program.name === undefined) {
      throw invalidArgument("restore needs a program");
    }
    if (!program.terminal) throw invalidArgument("only a finished program can be restored");
    if (this.#programs.has(program.name)) {
      throw new CoreError(CODES.NAME_TAKEN, `name ${program.name} is live`, { name: program.name });
    }
    this.#retired.set(program.name, program);
    return program;
  }

  // Every live program, in insertion order. `live(name)` is the lookup.
  list() {
    return [...this.#programs.values()];
  }
}
