// Resume control (spec §3.3).
//
// A parked program is resumed by another named program through `unpark(name,
// value?)`. Before the parked expression gets its value the host checks, in this
// order:
//   1. the controller observed the target in this request   STALE_CONTROL
//   2. the target name is not the controller itself         INVALID_ARGUMENT
//   3. the target exists                                    PROGRAM_UNKNOWN
//   4. the controller holds the target's capability set     CAPABILITY_DENIED
//   5. the target is still parked                           STALE_CONTROL
// Only then does the target leave `parked` for `running`, exactly once.
//
// `this.ask(question)` IS `this.park({ question })` + `interrupt`: the program
// parks exactly like an explicit park, and the operator's reply comes back
// through `unpark(name, answer)` like any other injected value. An `ask` site
// therefore takes the injected `value` (the answer), the same as an explicit
// `park` and a `where` ambiguity. Only a `call` site is different: it gets the
// command's real receipt and never the injected value. Omitting `value` injects
// null.
//
// What the controller gets back is a program receipt of the resumed target
// (spec §3.3: a control program returns "the target's current receipt"), with
// the value the parked expression receives attached as `value`. A plain
// `{site, value}` pair would let a controller read the parked payload without
// the state it belongs to.

import { CoreError, CODES, invalidArgument } from "./errors.mjs";
import { PARK_REASONS, programView, withExtra } from "./receipt.mjs";
import { stableJson } from "./util.mjs";

// The controller MUST hold every capability the target requires; extra
// capabilities are allowed.
const holdsAll = (held, required) => {
  if (!Array.isArray(held) || !Array.isArray(required)) return false;
  const owned = new Set(held);
  return required.every((capability) => owned.has(capability));
};

// One controller's observations inside one request.
export class ObservationLedger {
  #seen;

  constructor(controllerId) {
    this.controllerId = controllerId;
    this.#seen = new Set();
  }

  mark(name) {
    this.#seen.add(name);
    return this;
  }

  has(name) {
    return this.#seen.has(name);
  }

  get size() {
    return this.#seen.size;
  }
}

export class ControlSurface {
  #registry;
  #ledgers;

  constructor(registry) {
    if (registry === null || typeof registry.get !== "function") {
      throw new TypeError("ControlSurface needs a program registry");
    }
    this.#registry = registry;
    this.#ledgers = new Map();
  }

  // Open the observation ledger of a request. The host calls this once per
  // request; `observe` writes into the current ledger.
  open(controllerId) {
    const ledger = new ObservationLedger(controllerId);
    this.#ledgers.set(controllerId, ledger);
    return ledger;
  }

  close(controllerId) {
    return this.#ledgers.delete(controllerId);
  }

  observe(controllerId, name) {
    const ledger = this.#ledgers.get(controllerId);
    if (ledger === undefined) {
      throw invalidArgument(`no open request for controller ${controllerId}`);
    }
    ledger.mark(name);
    return name;
  }

  // Every park still unsettled for one controller, in lexical order. This is
  // the handle list the Pair Hint shows, and it never resumes anything.
  handles(controllerId) {
    const ledger = this.#ledgers.get(controllerId);
    if (ledger === undefined) return [];
    const names = [];
    for (const program of this.#registry.list()) {
      if (program.state === "parked" && ledger.has(program.name)) names.push(program.name);
    }
    return names.sort();
  }

  // The resume itself. The checks run in the order spec §3.3 gives, and the
  // order is part of the contract:
  //
  //   1 observation      STALE_CONTROL
  //   2 self-target      INVALID_ARGUMENT
  //   3 existence        PROGRAM_UNKNOWN
  //   4 capability set   CAPABILITY_DENIED   (stated set, then subset)
  //   5 still parked     STALE_CONTROL
  //
  // Capability before parked-state is deliberate, not incidental: "全部通过后
  // 把目标从 `parked` 切回 `running`" puts the state change after every check,
  // so a controller that does not hold the set cannot learn a program's state
  // by observing the difference between `STALE_CONTROL` and `CAPABILITY_DENIED`.
  //
  // `capabilities` are the controller's; the required set is the target
  // program's own (`ProgramState#capabilities`), so a controller cannot lower
  // the bar by omitting it. `targetCapabilities` is accepted only as an
  // explicit override for a host that mirrors the set elsewhere, and it is
  // checked against the program: a controller that claims a different set than
  // the target carries is refused. `deadline` is the controlling request's
  // deadline, which the resumed run continues on.
  //
  // `settle` is the host's continuation hook, and it is the reason this can
  // answer the resumed program's receipt. A host that keeps the parked body in
  // its own park table passes `(resumeValue) => receipt`; it settles the body
  // (which lets the body run on past the parked expression) and answers the
  // receipt of that continuation. Core cannot build that receipt: it never sees
  // the body return. A host without one gets core's view of the resumed program
  // exactly as before.
  unpark({
    controllerId,
    name,
    value = null,
    capabilities = [],
    targetCapabilities = null,
    deadline = null,
    settle = null,
  }) {
    const ledger = this.#ledgers.get(controllerId);
    if (ledger === undefined || typeof name !== "string" || name.length === 0 || !ledger.has(name)) {
      throw new CoreError(
        CODES.STALE_CONTROL,
        `controller ${controllerId} did not observe ${name} in this request`,
        { name },
      );
    }
    if (name === controllerId) {
      throw invalidArgument("a program cannot unpark itself", { name });
    }
    const target = this.#registry.get(name);
    if (target === null) {
      throw new CoreError(CODES.PROGRAM_UNKNOWN, `program ${name} is unknown`, { name });
    }
    const required = target.capabilities;
    if (targetCapabilities !== null && stableJson(targetCapabilities) !== stableJson(required)) {
      throw new CoreError(
        CODES.CAPABILITY_DENIED,
        `the stated capability set of ${name} is not the target's own`,
        { name, required },
      );
    }
    if (!holdsAll(capabilities, required)) {
      throw new CoreError(
        CODES.CAPABILITY_DENIED,
        `controller ${controllerId} lacks the capability set of ${name}`,
        { name },
      );
    }
    if (target.state !== "parked") {
      throw new CoreError(CODES.STALE_CONTROL, `program ${name} is not parked`, {
        name,
        state: target.state,
      });
    }
    const site = target.takeSite({ deadline });
    const resumed = resumeValue(site, value);
    // The body continues first, so the receipt that follows describes what the
    // resumed program actually did (根 §3.3: "the target's current receipt").
    if (typeof settle === "function") {
      const settled = settle(resumed);
      if (settled !== null && typeof settled === "object") {
        // `value` is the parked expression's value, which `resumeValue` decided
        // and which the hook already received. It is re-stated here so the
        // controller reads it from the receipt whatever the continuation says.
        return withExtra({ ...settled }, "value", resumed);
      }
    }
    // Built after `takeSite`, so the receipt reports the target as `running`
    // with the controlling request's deadline; `reason` and `payload` are the
    // consumed site's own, which is what the controller observed. The site is
    // passed explicitly because the program no longer holds it.
    return withExtra(
      programView(target, { source: null, command: null, delta: "", site }),
      "value",
      resumed,
    );
  }
}

// What the parked expression receives (spec §3.3): `value` goes to an explicit
// `park`, a `where` ambiguity and an `ask`. `this.ask` is `this.park` plus
// `interrupt`, so the operator's reply is just the injected value — there is no
// second channel and no site-stored answer. Only a `call` site takes its own
// result: a command receipt is produced by the command, and letting a
// controller inject one would forge an execution that never happened.
//
// A site may still carry its own `value` (a host that recorded the answer
// before resuming). That value is the fallback for an `ask` site resumed
// without an argument, never a replacement for one the controller passed.
export const resumeValue = (site, value) => {
  if (site.reason === PARK_REASONS.COMMAND) {
    return site.value ?? null;
  }
  if (value !== null && value !== undefined) return value;
  if (site.reason === PARK_REASONS.ASK && site.value !== undefined) return site.value;
  // `ControlSurface#unpark` defaults an omitted `value` to `null` before this
  // function sees it (§3.3: an omitted value is equivalent to injecting null),
  // so a real resume never reaches here with `undefined`. A direct caller that
  // does passes it through when the site has no recorded answer; an `ask` with
  // a recorded answer already fell back to it above.
  return value;
};
