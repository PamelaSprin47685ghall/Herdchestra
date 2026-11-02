// Program execution (spec §3.3, §3.4).
//
// One `js-*` call is one program. The body is real JavaScript: it is wrapped in
// an async function so `await` works at its top level, and the only names in
// scope are the surface members compose decided for this role. A member that
// parks leaves a promise in the park table; the call answers at once with the
// park receipt, and another program resumes the parked expression through
// `unpark`.
//
// The body is not sandboxed beyond this process. The boundary the spec draws is
// the member list: a member the role lacks is not in scope, so calling it is a
// TypeError rather than a silent no-op.
//
// A park is not a stop of the BODY. The body is still awaiting the promise the
// member returned, so every local variable and every statement after the parked
// expression survives in memory; resuming the promise (through core's control
// surface, or through the operator's answer to `ask`) continues the same
// program.
//
// The shape that makes a resume observable is worth stating exactly, because
// everything else in this file follows from it:
//
//   callA.run(body)                 starts the body; the body is a promise
//   body  -> await this.ask(q)      parks: the entry holds `resolve`
//   callA.run(...)                  RETURNS { status: "parked" } at once, and
//                                   the tool call answers with the park receipt
//   bodyPromise                     STILL SUSPENDED on that promise
//
// So the entry must keep `bodyPromise` (the continuation) beside `resolve` (its
// wake-up). A controller that resumes it waits on the CONTINUATION, not on the
// tool call that already answered: `resume()` wakes the body and then races the
// continuation against the program's next park, so `this.unpark` answers the
// resumed program's terminal receipt — or its next parked receipt when the
// resumed body parks again (根 §3.3).

import { CoreError, CODES } from "../core/errors.mjs";
import { STATES } from "../core/program.mjs";

const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

/**
 * Park table: the promise a parked member waits on, keyed by program name.
 *
 * One entry is one program that has suspended:
 *
 *   site          the park site (`{ reason, payload }`)
 *   program       the `ProgramState` whose body is waiting
 *   surface       the surface that describes the continuation in a receipt
 *   promise       the promise the parked member awaits
 *   resolve       its wake-up
 *   bodyPromise   the body itself, still suspended on `promise`
 *   nextPark      a promise resolved by the program's NEXT park, so a resume
 *                 can race the continuation against it
 */
export class ParkTable {
  entries = new Map();

  open(name, { program = null, surface = null, bodyPromise = null, call = null } = {}) {
    const existing = this.entries.get(name);
    // A name whose program finished left nothing parked: the entry is dead and
    // is replaced. A name that is still parked or still holds a body is a real
    // double open, and the registry's `NAME_TAKEN` is what the caller reads.
    if (existing !== undefined && (existing.promise !== null || existing.resolve !== null)) {
      throw new CoreError(CODES.NAME_TAKEN, `program ${name} is already open`, { name });
    }
    if (existing !== undefined) this.entries.delete(name);
    const entry = {
      name,
      promise: null,
      resolve: null,
      site: null,
      program,
      surface,
      bodyPromise,
      call,
      nextPark: null,
      wakeNextPark: null,
    };
    this.entries.set(name, entry);
    return entry;
  }

  park(name, site, { program = null, surface = null, bodyPromise = null, call = null } = {}) {
    const entry = this.entries.get(name);
    if (entry === undefined) throw new CoreError(CODES.PROGRAM_UNKNOWN, `program ${name} is not open`, { name });
    if (entry.promise !== null) {
      throw new CoreError(CODES.STALE_CONTROL, `program ${name} already has a parked expression`, { name });
    }
    entry.site = site;
    // A caller that opens the entry before the call exists (a host that parks
    // through its own channel) names them here; a call that opened the entry
    // already has them.
    if (program !== null) entry.program = program;
    if (surface !== null) entry.surface = surface;
    if (bodyPromise !== null) entry.bodyPromise = bodyPromise;
    if (call !== null) entry.call = call;
    entry.promise = new Promise((resolve) => {
      entry.resolve = resolve;
    });
    // A resume of the PREVIOUS park races the continuation against this one.
    // Announcing it here is what lets `this.unpark` answer "parked again" with
    // the new site instead of waiting for a body that will never return.
    if (typeof entry.wakeNextPark === "function") {
      const wake = entry.wakeNextPark;
      entry.wakeNextPark = null;
      entry.nextPark = null;
      wake({ parked: true, site });
    }
    return entry.promise;
  }

  /**
   * What a parked program waits on, or null when it is not parked. A reader
   * that needs the continuation itself (the `ask` interrupt, a control program
   * resuming it) reads it here instead of guessing from the program state.
   */
  siteOf(name) {
    const entry = this.entries.get(name);
    if (entry === undefined || entry.resolve === null) return null;
    return entry.site;
  }

  /**
   * Resume once and answer the continuation.
   *
   * `settle` wakes the parked expression and returns `false` when nothing is
   * parked, which the caller reports. `resume` is the same wake-up followed by
   * the continuation: it resolves `{ status, value | error | site }` where
   * `status` is `"completed"`, `"failed"` or `"parked"` (the body parked again
   * before returning), so a controller can answer the resumed program's own
   * receipt (根 §3.3).
   */
  settle(name, value) {
    const entry = this.entries.get(name);
    if (entry === undefined || entry.resolve === null) return false;
    // The race against the program's NEXT park is armed BEFORE the wake-up: the
    // resumed body may park again synchronously, and a listener armed after the
    // wake would miss it. `continuation()` below awaits this.
    entry.nextPark = new Promise((wake) => {
      entry.wakeNextPark = wake;
    });
    const resolve = entry.resolve;
    // The site the controller RESOLVED is kept: a receipt that describes this
    // resume must still name the park it acted on, and `entry.site` is cleared
    // below so the next park can take it.
    entry.resolvedSite = entry.site;
    entry.promise = null;
    entry.resolve = null;
    entry.site = null;
    resolve(value);
    return true;
  }

  /**
   * Await the continuation a `settle` started, and normalize its outcome.
   *
   * This is split from `settle` because a host that resumes through core cannot
   * control WHEN `settle` runs — core calls it in the middle of `unpark` — but
   * it can await afterwards. Splitting also keeps the wake-up synchronous: the
   * parked expression has its value before `settle` returns.
   */
  async continuation(name) {
    const entry = this.entries.get(name);
    if (entry === undefined) return false;
    const nextPark = entry.nextPark;
    const body = entry.bodyPromise;
    if (body === null || typeof body?.then !== "function") {
      entry.wakeNextPark = null;
      entry.nextPark = null;
      return { status: "resumed" };
    }
    let outcome;
    try {
      outcome = await Promise.race([body, nextPark]);
    } catch (error) {
      return { status: "failed", error: { name: error?.name ?? "Error", message: String(error?.message ?? error) } };
    } finally {
      entry.wakeNextPark = null;
      entry.nextPark = null;
    }
    if (outcome !== null && typeof outcome === "object" && outcome.parked === true) {
      return { status: "parked", site: outcome.site };
    }
    return { status: "completed", value: outcome };
  }

  async resume(name, value) {
    // One implementation, two entry points: a caller that owns both halves
    // (this session's own resume) uses `resume`; a caller whose wake-up runs
    // inside someone else's function (core's `unpark`) calls `settle` there and
    // `continuation` here.
    if (!this.settle(name, value)) return false;
    return this.continuation(name);
  }

  close(name) {
    const entry = this.entries.get(name);
    if (entry !== undefined && entry.resolve !== null) {
      entry.resolve(undefined);
      entry.promise = null;
      entry.resolve = null;
      entry.site = null;
    }
    if (entry !== undefined && typeof entry.wakeNextPark === "function") {
      const wake = entry.wakeNextPark;
      entry.wakeNextPark = null;
      // A closed program will never park again: the race is released with the
      // body's own outcome instead of hanging.
      wake({ status: "closed" });
    }
    return this.entries.delete(name);
  }
}

/** One program call: its state, its park promise, and the one place it ends. */
export class ProgramCall {
  #program;
  parks;
  #surface = null;
  // The registry this program's name belongs to. `run` retires the name when it
  // sees the body end, but a body that ended AFTER `run` answered a park needs
  // the same step from `settleContinuation`, or a finished program keeps its
  // name and no later call can use it.
  #registry = null;
  #settled = false;
  #parkedSite = null;
  // The body's own promise, set by `run`. It is what a resume waits on: the
  // tool call answered at the park, but the body is still suspended.
  #body = null;
  // The first park's signal. `run` races the body against it, because a body
  // that parks NEVER returns: its `await this.ask(...)` (or `this.park`, or a
  // `where` ambiguity) waits for a resume that may come a whole turn later.
  // Awaiting the body alone would hold the tool call open forever — the live
  // hang that made every park test time out.
  #firstPark = null;
  #resolveFirstPark = null;

  constructor({ program, parks, surface = null, registry = null }) {
    this.#program = program;
    this.parks = parks;
    this.#surface = surface;
    this.#registry = registry;
    this.#firstPark = new Promise((resolve) => {
      this.#resolveFirstPark = resolve;
    });
    // The entry exists from the moment the call does: `park` needs it, and so
    // does a later reader of `siteOf`/`resume`. A name already taken is the
    // registry's refusal (it began this program), so a failure here is a real
    // wiring error and is allowed to throw.
    parks.open(program.name, { program, surface, call: this });
  }

  get program() {
    return this.#program;
  }

  get surface() {
    return this.#surface;
  }

  get parkedSite() {
    return this.#parkedSite;
  }

  get settled() {
    return this.#settled;
  }

  get body() {
    return this.#body;
  }

  /**
   * Attach the surface that describes this program's receipts. The surface is
   * built from the call's own park channel, so it cannot exist before the call
   * does; this is the one late binding, and it happens before any body runs.
   */
  attachSurface(surface) {
    if (surface !== null && surface !== undefined) this.#surface = surface;
    return this;
  }

  park(site) {
    const state = this.#program.state;
    if (state !== STATES.RUNNING && state !== STATES.WAITING) {
      throw new CoreError(CODES.INVALID_ARGUMENT, `program ${this.#program.name} is ${state}`, {
        name: this.#program.name,
      });
    }
    this.#program.park(site);
    this.#parkedSite = site;
    // The promise the parked member awaits resolves when another program
    // resumes it. Resolving to the parked view keeps `await this.where(...)`
    // shaped like a receipt even on the resume path.
    const view = {
      name: this.#program.name,
      state: STATES.PARKED,
      reason: site.reason,
      payload: site.payload ?? null,
    };
    // The entry keeps the program and the surface: they are the continuation a
    // later reader (`unpark`, the `ask` interrupt) needs, and the surface is
    // built before this call runs, so it is always here.
    const waitPromise = this.parks.park(this.#program.name, site, {
      program: this.#program,
      surface: this.#surface,
      bodyPromise: this.#body,
      call: this,
    });
    // The tool call has to answer NOW: this is the suspension the BODY waits
    // on, not the suspension the CALL waits on. `run` is racing the body
    // against this signal, so it returns the park receipt while `waitPromise`
    // keeps the body suspended until a resume.
    if (this.#resolveFirstPark !== null) {
      const wake = this.#resolveFirstPark;
      this.#resolveFirstPark = null;
      wake({ status: STATES.PARKED, site });
    }
    return waitPromise.then((value) => (value === undefined ? view : value));
  }

  /**
   * Finish this call from a continuation that ran after `run` answered.
   *
   * `run` answers a park at once, so the body keeps running later; when a resume
   * lets it end, nothing else in the process would ever finish the program —
   * `run` has already returned. This is that missing step: the program reaches
   * its terminal state, the name is retired, and the surface's receipt is the
   * call's own final one (根 §3.3).
   *
   * Returns the same shape `run` would have returned.
   */
  settleContinuation(outcome) {
    const retired = () => {
      if (this.#registry !== null && typeof this.#registry?.retire === "function") {
        this.#registry.retire(this.#program.name);
      }
    };
    if (outcome?.status === "completed") {
      this.finish(STATES.COMPLETED, outcome.value);
      retired();
      return { status: "completed", value: outcome.value };
    }
    if (outcome?.status === "failed") {
      const error = outcome.error ?? { name: "Error", message: "the resumed body failed" };
      const failure = {
        name: error?.name ?? "Error",
        message: String(error?.message ?? error),
        ...(typeof error?.code === "string" ? { code: error.code } : {}),
      };
      this.finish(STATES.FAILED, failure);
      retired();
      return { status: "failed", error: failure };
    }
    // Still parked: the program is not finished, and the entry must survive for
    // the next resume.
    return { status: "parked" };
  }

  finish(state, payload) {
    if (this.#settled) return;
    this.#settled = true;
    if (!this.#program.terminal) this.#program.finish(state, payload);
    this.parks.close(this.#program.name);
  }

  /**
   * Run the body and decide the call:
   *   { status: "parked" }            a member parked; the receipt is ready
   *   { status: "completed", value }  the body returned
   *   { status: "failed", error }     the body threw
   */
  async run(body) {
    // The body starts here and may suspend inside; keeping the promise is what
    // makes a later resume observable. `body()` is called ONCE: awaiting the
    // same promise twice is free, calling the function twice is not.
    const started = body();
    this.#body = started;
    // A park may already have happened synchronously (a `where` ambiguity on the
    // first statement), so the entry is told the body it must wake.
    const entry = this.parks.entries.get(this.#program.name);
    if (entry !== undefined) entry.bodyPromise = started;

    // The body NEVER returns while it is parked: it is awaiting the resume. So
    // the call answers whichever comes first — the body's own end, or its first
    // park. Awaiting the body alone is the live hang this race fixes.
    const raced = await Promise.race([
      started.then(
        (value) => ({ status: STATES.COMPLETED, value }),
        (error) => ({ status: STATES.FAILED, error }),
      ),
      this.#firstPark,
    ]);

    if (raced.status === STATES.PARKED || this.#program.state === STATES.PARKED) {
      // A parked body still runs later. Its eventual rejection belongs to the
      // program that resumes it (`ParkTable.resume` awaits it), and an unhandled
      // rejection here would take the process down for a body nobody is waiting
      // on yet.
      started.catch(() => {});
      return { status: "parked", site: this.#parkedSite };
    }
    if (raced.status === STATES.FAILED) {
      // The refusal's code travels with the failure: `ASK_FORBIDDEN_OUTSIDE_S1`
      // is read by its code, and a receipt that kept only the message would
      // make the one refusal the spec names by code unreadable.
      const error = raced.error;
      const failure = {
        name: error?.name ?? "Error",
        message: String(error?.message ?? error),
        ...(typeof error?.code === "string" ? { code: error.code } : {}),
      };
      this.finish(STATES.FAILED, failure);
      return { status: "failed", error: failure };
    }
    this.finish(STATES.COMPLETED, raced.value);
    return { status: "completed", value: raced.value };
  }
}

// Top-level primitives (root AGENTS.md §3.1, §1.1 item 3): one spelling each,
// and it is the bare name. They are not members, so a program has no
// `this.fork` — and Plan has no `this.devops`.
//
// `devops` is here because Plan's investigation is a JOIN, not a member call
// (root §1.1 item 3): the program waits for the dedicated worker's complete
// record and continues with it. Compose keeps it out of Plan's member table
// (`SURFACE_PRIMITIVES`), and `SessionState.surface` binds the one handler the
// session's dedicated runner answers. A role whose members do not carry the
// name gets no binding at all.
const PRIMITIVES = Object.freeze(["fork", "join", "review", "devops"]);

/**
 * Compile a program (§3.4 writes it as `class Js extends JsProgram { async
 * run() {...} }`). One capability has exactly one spelling (root §3.1): every
 * member is reached through `this` only, so the members are bound as the `this`
 * of the body and are NOT also parameters; the three top-level primitives are
 * the reverse, bound as parameters only and never put on `this`.
 *
 * `AsyncFunction` may not declare a parameter named `this` (a strict-mode
 * syntax error), so `this` comes from `bind` alone. The body runs inside an
 * async arrow, which has no `this` of its own, so `this.cat(...)` reaches the
 * bound members.
 */
export const compile = (code, members) => {
  const primitives = {};
  const instance = {};
  for (const [name, handler] of Object.entries(members)) {
    if (PRIMITIVES.includes(name)) primitives[name] = handler;
    else instance[name] = handler;
  }
  const names = Object.keys(primitives);
  // `__surface` is the object the `JsProgram` base class reads in its
  // constructor. It carries the members only: the primitives are parameters,
  // so putting them on `this` too would give one capability two spellings.
  const body = `return (async () => {\n${programBody(code)}\n})();`;
  return new AsyncFunction("__surface", ...names, body).bind(
    instance,
    { ...instance },
    ...names.map((name) => primitives[name]),
  );
};

/**
 * The program the body runs. A body that does not declare its own class is
 * wrapped in one, so both forms reach the members the same way: through the
 * instance. `this.fork`/`this.join`/`this.review` are absent on purpose (§3.1:
 * the primitives are bare names only).
 */
export const programBody = (code) =>
  /\bextends\s+JsProgram\b/.test(code)
    ? [
        "class JsProgram {",
        "  constructor(surface) { Object.assign(this, surface); }",
        "}",
        code,
        "return await new Js(__surface).run();",
      ].join("\n")
    : [
        "class JsProgram {",
        "  constructor(surface) { Object.assign(this, surface); }",
        "}",
        "class Js extends JsProgram {",
        "  async run() {",
        code,
        "  }",
        "}",
        "return await new Js(__surface).run();",
      ].join("\n");
