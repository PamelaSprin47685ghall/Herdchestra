// Program execution with a real suspend/resume (spec §3.3).
//
// The model writes `class Js extends JsProgram { async run() { ... } }`. The
// class is compiled into a subclass of `JsProgram`, its members are bound onto
// the instance, and `run()` is awaited.
//
// ## One spelling per capability (root AGENTS.md §3.1)
//
// A member is reached through `this` only: `this.cat(...)`, `this.park(...)`.
// The three top-level primitives — `fork`, `join`, `review` — are the reverse:
// they are plain functions in the body's scope and are NEVER put on the
// instance, so `this.fork` is a TypeError and there is no bare `cat`.
//
// ## How a park suspends the call
//
// compose's surface parks a program by calling core's `ProgramState.park` and
// returns the receipt to the member. The body must not see that receipt: the
// member call has to stay pending, so the body's own async stack stays intact.
// Every member is therefore wrapped: after the call, if the program is parked,
// the wrapper returns the promise kept in `ParkTable` instead of the receipt.
//
//   const text = await this.cat("a");            // resolves normally
//   const span = await this.where(text, "x");    // parks: this await stays pending
//
// The tool call answers with the park receipt at once, because it does not await
// the body — it races the body's outcome against a park signal. `unpark` from
// another program settles exactly that promise, so the body continues at the
// park site and its remaining statements run once. Nothing before the park runs
// again (spec §3.3 "恢复不重跑").
//
// ## Endings
//
//   completed  `run()` returned: settle(completed), then the terminal receipt;
//   failed     `run()` threw: settle(failed), the error is reported;
//   parked     a member parked: the program stays live, its name is held, and
//              the tool answers with the receipt. The resumed body's own end
//              settles the program and retires the name.
//
// A compile failure is not a run: `compileProgram` throws a `TypeError` before
// any program exists, and nothing is settled.

import { STATES } from "../core/program.mjs";
import { extraFactsOf } from "./tool-schema.mjs";

// The top-level primitives (§3.1): bare function names only, never members.
const PRIMITIVES = Object.freeze(["fork", "join", "review"]);

// The members a program instance receives, in the order they are bound.
const MEMBERS = Object.freeze([
  "cat",
  "stat",
  "ls",
  "write",
  "mkdir",
  "rm",
  "mv",
  "where",
  "whereAll",
  "park",
  "unpark",
  "call",
  "ask",
  "fission",
  "devops",
]);

export const isPrimitive = (name) => PRIMITIVES.includes(name);

export class JsProgram {
  constructor(surface) {
    if (surface === null || typeof surface !== "object") {
      throw new TypeError("JsProgram needs a program surface");
    }
    this.surface = surface;
    // One spelling per capability (§3.1): the members are `this.xxx` only and
    // the primitives are bare names only. A subclass whose prototype carries a
    // member name would put a second spelling of that member in scope, so such
    // a program is refused here instead of running with two vocabularies.
    for (const name of Object.keys(surface)) {
      if (isPrimitive(name) || name === "surface") continue;
      if (Object.getOwnPropertyDescriptor(Object.getPrototypeOf(this), name) !== undefined) {
        throw new TypeError(
          `a program may not declare \`${name}\`: members are reached as this.${name}, so a method of that name would be a second spelling`,
        );
      }
    }
  }

  // `this.ask(question)` (spec §3.3 example 7, §6). Only a top-level face has
  // the member; a worker has none, so its call is the same TypeError as any
  // other missing member. The question parks the program, so the call stays
  // pending exactly like `park` does.
  async ask(question) {
    const ask = this.surface.ask;
    if (typeof ask !== "function") {
      throw new TypeError("this.ask is not available to this program");
    }
    return await ask(question);
  }

  async run() {
    throw new Error("a program must define async run()");
  }
}

// One parked member call. `promise` is what the body's `await` is blocked on;
// `settle` resumes it exactly once with the injected value.
export class ParkTable {
  #entries;

  constructor() {
    this.#entries = new Map();
  }

  get size() {
    return this.#entries.size;
  }

  has(name) {
    return this.#entries.has(name);
  }

  // Register the park of `name`. A second park before a resume is a control
  // error: one parked site per program (spec §3.3, single-shot resume).
  open(name, site) {
    if (this.#entries.has(name)) {
      throw Object.assign(new Error(`program ${name} already has a parked expression`), {
        code: "STALE_CONTROL",
      });
    }
    const entry = { name, site, resolve: null, promise: null };
    entry.promise = new Promise((resolve) => {
      entry.resolve = resolve;
    });
    this.#entries.set(name, entry);
    return entry.promise;
  }

  // Resume once. Returns false when nothing is parked under this name.
  settle(name, value) {
    const entry = this.#entries.get(name);
    if (entry === undefined || entry.resolve === null) return false;
    const resolve = entry.resolve;
    entry.resolve = null;
    entry.promise = null;
    resolve(value);
    return true;
  }

  // The promise the body is waiting on, if this program is parked. The guard
  // asks for it before opening a new one: every member call made while the
  // program is parked must wait on the same promise, or a second call would
  // replace the entry and the body would wait on a promise nobody can settle.
  pending(name) {
    const entry = this.#entries.get(name);
    return entry === undefined || entry.resolve === null ? null : entry.promise;
  }

  // The name is gone. A still-pending promise is released with `undefined` so
  // its body does not hang forever on a program that can never be resumed.
  close(name) {
    const entry = this.#entries.get(name);
    if (entry === undefined) return false;
    if (entry.resolve !== null) entry.resolve(undefined);
    return this.#entries.delete(name);
  }

  names() {
    return [...this.#entries.keys()];
  }
}

// Wrap the members so a call that parked the program returns the park promise
// instead of the receipt. The receipt itself is what the tool call answers.
// Primitives are wrapped the same way: `review` writes the ledger and `join`
// waits, and either may be the call that parks.
export const guardedMembers = (members, program, parks) => {
  const wrapped = {};
  for (const [name, handler] of Object.entries(members)) {
    if (typeof handler !== "function") {
      wrapped[name] = handler;
      continue;
    }
    wrapped[name] = (...args) => {
      const wasParked = program.state === STATES.PARKED;
      const value = handler(...args);
      // Only the call that parked suspends. A member called while the program
      // is already parked (the resumed body's next statement before core moved
      // it back to `running`) is an ordinary call: suspending it again would
      // wait on a promise the resume already consumed.
      if (wasParked || program.state !== STATES.PARKED) return value;
      return parks.pending(program.name) ?? parks.open(program.name, program.site);
    };
  }
  return wrapped;
};

// Bind the declared members onto one program instance.
//
// The members arrive already bound to the surface: `startProgram` binds each
// one before guarding it, because the guard must see the call after the surface
// has run it. Binding again here would replace that `this` and lose the
// surface's own state, so a function is used as it is.
//
// A primitive is not a member (§3.1): it is handed to the body as a plain
// function instead, so the instance carries no `this.fork`.
export const bindMembers = (instance, surface) => {
  // The surface is the authority on its members: `MEMBERS` is the full
  // vocabulary a program may see, and the surface decides which of it this role
  // has. Anything else it offers is bound too, so a host or a test that adds a
  // member is not silently ignored.
  for (const member of new Set([...MEMBERS, ...Object.keys(surface)])) {
    const handler = surface[member];
    if (typeof handler !== "function" || isPrimitive(member)) continue;
    Object.defineProperty(instance, member, {
      value: handler,
      enumerable: false,
      writable: false,
      configurable: false,
    });
  }
  return instance;
};

// The primitives this surface offers, as an object of bare names. They are
// parameters of the compiled factory, never properties of the instance.
export const primitiveScope = (surface) => {
  const scope = {};
  for (const name of PRIMITIVES) {
    if (typeof surface[name] === "function") scope[name] = surface[name];
  }
  return scope;
};

// Compile the model's source into a subclass of `JsProgram`. The compiler is
// `AsyncFunction`, which keeps the model's own syntax: loops, conditionals,
// template strings and native string methods all work as written.
const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor;

// The class the source declares. The documented name is `Js`; any identifier is
// accepted, so a program is never rejected over its class name.
const CLASS_NAME = /\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+JsProgram\b/;

// The factory is an async function, so it hands back a promise for the class.
// Awaiting that promise would *invoke* the class with the resolved value as its
// constructor argument — a class is callable and `await` calls it — so the class
// is read out of the promise directly, never awaited a second time.
export const compileProgram = async (source, primitives = {}) => {
  if (typeof source !== "string" || source.trim() === "") {
    throw new TypeError("program source must be a non-empty string");
  }
  const declared = CLASS_NAME.exec(source);
  if (declared === null) {
    throw new TypeError("program must be `class Js extends JsProgram { async run() { ... } }`");
  }
  // The primitives are the only bare names the body may call (§3.1). They are
  // parameters, so a name the surface does not offer is a ReferenceError rather
  // than an `undefined is not a function` on a member nobody bound.
  const names = Object.keys(primitives);
  const factory = new AsyncFunction("JsProgram", ...names, `"use strict";\n${source}\nreturn ${declared[1]};`);
  // The class travels inside an object. A promise cannot resolve *to* a class:
  // both `await` and `then` treat a callable as a thenable/constructor and call
  // it, which would run `JsProgram(undefined)` and throw before any program ran.
  const { program: Program } = await new Promise((resolve, reject) => {
    factory(JsProgram, ...names.map((name) => primitives[name])).then(
      (value) => resolve({ program: value }),
      reject,
    );
  });
  if (typeof Program !== "function" || typeof Program.prototype?.run !== "function") {
    throw new TypeError("program must be `class Js extends JsProgram { async run() { ... } }`");
  }
  return Program;
};

// Start one program.
//
//   outcome  settles when the body returns or throws — never on a park;
//   parked   settles as soon as the program is parked, so the call can answer
//            with the receipt without waiting for the body.
//
// `onEnd(outcome)` runs once the body is finished (either way) with the settled
// program; the tool uses it to build the terminal receipt. It is not called for
// a park, because a parked body has not finished.
export const startProgram = ({ source, surface, program, parks, onEnd = null }) => {
  let finishOutcome;
  const outcome = new Promise((resolve) => {
    finishOutcome = resolve;
  });
  let finishPark;
  const parked = new Promise((resolve) => {
    finishPark = resolve;
  });
  let settled = false;

  // `bindMembers` binds each member to the surface object, so the body calls
  // the bound copy; the guard must wrap those, or a park never suspends.
  const members = typeof surface.surface === "function" ? surface.surface() : surface;
  const bound = {};
  for (const [name, handler] of Object.entries(members)) {
    bound[name] = typeof handler === "function" ? handler.bind(members) : handler;
  }
  const guarded = guardedMembers(bound, program, parks);
  // The primitives travel as bare parameters; the members travel on the
  // instance. One capability, one spelling (§3.1).
  const primitives = primitiveScope(guarded);

  void (async () => {
    let result;
    // Set once the model's body is about to run: a compile failure leaves it
    // false, so the program is neither completed nor failed as a run.
    let ran = false;
    try {
      const Program = await compileProgram(source, primitives);
      const instance = bindMembers(new Program(guarded), guarded);
      ran = true;
      const value = await instance.run();
      // The body RETURNED. A member call it left unawaited may still have parked
      // the program (the call's park is opened synchronously), and nothing will
      // resume a body that is over: the entry would keep the program's memory
      // for the life of the session, and the `finally` below would read
      // "parked" as a live run and keep it. A returned body's park is a park
      // nobody can use, so it is released here — before the state is read.
      if (program.state === STATES.PARKED) parks?.close(program.name);
      if (!program.terminal && program.state !== STATES.PARKED) {
        await surface.settle?.("completed", value ?? null);
      }
      result = { status: "completed", value: value ?? null };
    } catch (error) {
      // The failure record carries the facts the thrower attached beside the
      // message: a refusal that reports what it cleaned up (a fission
      // rollback) must not lose that report here. `name`/`message` are the
      // identity; every other enumerable own property is a fact.
      const failure = {
        name: error?.name ?? "Error",
        message: String(error?.message ?? error),
        ...(error?.code === undefined ? {} : { code: error.code }),
        ...extraFactsOf(error),
      };
      // A program that never ran is not settled: it did not complete and it did
      // not fail as a run, and finishing it would report a state nothing
      // reached.
      if (ran && !program.terminal && program.state !== STATES.PARKED) {
        await surface.settle?.("failed", failure);
      }
      result = { status: "failed", error: failure };
    }
    if (settled) return;
    settled = true;
    // The body is over: a park entry it left behind can never be resumed now,
    // so it is released before anyone reports the outcome.
    parks?.close(program.name);
    finishOutcome(result);
  })();

  // The body's promise settles only when it finishes, so a park is observed on
  // a short interval: a microtask is too early (the body has not reached its
  // first `await`), and one macrotask is too early when the body awaits real
  // I/O (a command) before it parks.
  const timer = setInterval(() => {
    if (settled || program.state !== STATES.PARKED) return;
    clearInterval(timer);
    finishPark({ status: "parked", site: program.site });
  }, 1);
  void outcome.finally(() => clearInterval(timer));

  return { outcome, parked };
};

export { MEMBERS as JS_PROGRAM_MEMBERS };
