// Program execution with a real suspend/resume (§3.3).
//
// The model writes a program as §3.4 shows:
//
//   class Js extends JsProgram {
//     async run() { ... }
//   }
//
// The source is compiled into a subclass of `JsProgram` (the same base the OMP
// adapter uses, `src/host-omp/program.mjs`), or, when it declares no such class,
// wrapped as the body of `run()`. One capability has one spelling (§3.1): the
// members are `this` properties only, and the top-level primitives (`fork`,
// `join`, `review`) are plain parameters only.
//
// ## How a park suspends the call
//
// compose's surface parks a program by calling core's `ProgramState.park` and
// returns the receipt to the member. The adapter must not let the body see that
// receipt: the member call has to stay pending. So the wrapper around each
// member checks the program after the call and, when it is parked, returns a
// promise that never settles on its own:
//
//   const t = await this.cat("a");          // resolves normally
//   const span = await this.where(t, "x");  // parks: this await never completes
//
// The body's own promise chain therefore stays intact. The call answers with
// the park receipt at once (the body is not awaited), and the promise the
// `await` is blocked on is kept in `ParkTable`. `unpark` from another program
// settles exactly that promise with the injected value, so the original async
// stack continues at the park site and runs its remaining code once. Nothing is
// re-run: statements before the park already ran and do not run again.
//
// A parked program keeps the name of the call that parked it. When the resumed
// body finishes, it records the final receipt on the same program.

import { STATES } from "../../core/program.mjs";
import { JsProgram } from "../../host-omp/program.mjs";

const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

// A source that declares `class ... extends JsProgram` is a program class; any
// other source is the body of `run()`.
const CLASS_PROGRAM = /\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+JsProgram\b/;

// Wrap the surface members. A member call that left the program parked returns
// the park promise instead of the receipt, so the caller's `await` suspends
// where it stands and the body's promise chain stays alive.
//
// `onParked(site)` runs synchronously at the moment of the park, before the
// pending promise is returned: the call's own `parked` signal and the park
// table entry both exist before any other task can observe the program as
// parked. Without it a park that happens before the body's first await is only
// discovered by polling, and a resume arriving in between finds no entry.
export const guardedMembers = (members, program, parks, onParked = () => {}) => {
  const wrapped = {};
  for (const [name, handler] of Object.entries(members)) {
    if (typeof handler !== "function") {
      wrapped[name] = handler;
      continue;
    }
    wrapped[name] = (...args) => {
      const value = handler(...args);
      if (program.state !== STATES.PARKED) return value;
      // The member parked through the surface's ONE channel, so the promise a
      // resume settles already exists. The body's `await` must receive THAT
      // promise: returning the member's own value would let it run on as though
      // the park had not happened. The signal is reported here, at the moment
      // the call sees the park, so the executor can answer with the receipt.
      if (parks.has(program.name)) {
        onParked(program.site);
        return parks.take(program.name)?.promise ?? value;
      }
      // The receipt is what the call answers; the body waits on the park.
      const pending = parks.park(program.name, program.site);
      onParked(program.site);
      return pending;
    };
  }
  return Object.freeze(wrapped);
};

// The top-level primitives of §3.1: bare names in the body, never members.
// A surface may add one (Plan's `devops`, root §1.1 item 3), so the list is
// supplied per program; this is the shared floor.
const PRIMITIVES = Object.freeze(["fork", "join", "review"]);

// Compile a program. Members reach the body through `this` alone; the
// primitives reach it as parameters alone (§3.1: no capability has two
// spellings, so there is no bare `cat(...)` and no `this.fork`).
export const compile = (code, members, Base, primitiveNames = PRIMITIVES) => {
  if (typeof code !== "string" || code.trim() === "") throw new TypeError("program source must be a non-empty string");
  const primitiveSet = new Set(primitiveNames);
  const instance = {};
  const primitives = {};
  for (const [name, handler] of Object.entries(members)) {
    if (primitiveSet.has(name)) primitives[name] = handler;
    else instance[name] = handler;
  }
  const names = Object.keys(primitives);
  const values = names.map((name) => primitives[name]);
  const parameters = ["JsProgram", "surface", ...names].join(", ");
  const declared = CLASS_PROGRAM.exec(code);
  const body =
    declared === null
      // A body that declares no class becomes the body of `run()`, so it reaches
      // its members exactly as a declared class does: through the instance.
      ? `"use strict";\nreturn (async () => {\n  class JsProgram { constructor(surface) { Object.assign(this, surface); } }\n  class Js extends JsProgram { async run() {\n${code}\n  } }\n  return await new Js(surface).run();\n})();`
      : `"use strict";\n${code}\nreturn (async () => {\n  const program = new ${declared[1]}(surface);\n  for (const name of Object.keys(surface)) program[name] = surface[name];\n  return await program.run();\n})();`;
  // `surface` is the members object; the class path copies it onto the instance
  // and the wrapped body path hands it to the constructor. Either way the
  // members are `this.xxx` and nothing else.
  const fn = new AsyncFunction(parameters, body);
  // A base class is supplied for the wrapped body only; a declared class uses
  // the parameter. `this` of the factory is never read: both paths go through
  // the instance.
  return fn.bind(undefined, Base, { ...instance }, ...values);
};

// Start one program.
//
// `outcome` settles when the body finishes or throws — never on a park, because
// the body is then still suspended. `parked` settles as soon as the program is
// parked, so the call can answer with the receipt without waiting for the body.
//
// A park has ONE spelling on the host: the surface's park channel. Some members
// park synchronously (the channel already suspended the program by the time the
// member returns); others park LATER, from a queued step — the ledger's first
// question (根 §2.4 第 1 步) is asked from `review`, which queues its decision, so
// the member has returned before the channel runs. The call's own park signal is
// therefore created HERE, once, and fired by both paths: `reportPark` for a park
// the channel performed (the host calls it there), and the wrapper's check for a
// park a member made synchronously. `signal` is idempotent, so whichever path
// fires first is the one the call answers with.
export const startProgram = ({ code, members, program, Base = JsProgram, parks, primitives = PRIMITIVES }) => {
  let finishOutcome;
  let finishPark;
  const outcome = new Promise((resolve) => {
    finishOutcome = resolve;
  });
  const parked = new Promise((resolve) => {
    finishPark = resolve;
  });
  let settled = false;
  let sawPark = false;
  const signal = (site) => {
    if (settled || sawPark) return;
    sawPark = true;
    finishPark({ status: "parked", site });
  };
  // The wrapper reports a park it saw at the instant it happened; the host
  // reports the one its channel performed.
  const guarded = guardedMembers(members, program, parks, signal);

  void (async () => {
    try {
      const value = await compile(code, guarded, Base, primitives)();
      // A body that ran to the end is finished, even when it was parked and
      // resumed on the way: the parked site was consumed by the resume, so the
      // program is no longer suspended (spec §3.3: the resume continues the run,
      // it does not leave it parked).
      if (!program.terminal) program.finish(STATES.COMPLETED, value);
      if (!settled) {
        settled = true;
        finishOutcome({ status: "completed", value });
      }
    } catch (error) {
      // A refusal carries its CODE (`ComposeError.code`), and the code is what
      // the model must read to know which rule it broke: the sentence alone
      // ("this.ask is only allowed during Plan milestone S1") names no rule.
      // The code leads the message the same way the host's own hook errors do,
      // so a caller can match on either without the record losing either.
      const code = typeof error?.code === "string" && error.code.length > 0 ? error.code : null;
      const sentence = String(error?.message ?? error);
      const failure = {
        name: error?.name ?? "Error",
        ...(code === null ? {} : { code }),
        message: code === null ? sentence : `${code}: ${sentence}`,
      };
      if (!program.terminal) program.finish(STATES.FAILED, failure);
      if (!settled) {
        settled = true;
        finishOutcome({ status: "failed", error: failure });
      }
    }
  })();

  return { outcome, parked, reportPark: signal };
};
