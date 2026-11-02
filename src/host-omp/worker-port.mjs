// Worker control (spec §4.1, §7.3): `fork`, `join`, and the steer channel.
//
// `fork` starts a real OMP session through the SDK the host injects
// (`api.pi.createAgentSession`, see `lane.mjs`). The host's `task` tool is not
// reachable from an extension, so the adapter drives the session itself: the
// lane creates it with the worker identity, initializes its extension runtime,
// authorizes it, and starts its first turn. The roster is the CALLING session's
// own `LaneTable`, so a worker's `fork` fills the worker's roster and the
// Manager's `join` never sees a worker it did not start.
//
// `fork` returns as soon as the first turn is submitted (the host's
// `agent_start`), never when the turn ends: that is the fire-and-forget half.
// `join` waits for the first lane of the caller's roster to leave a report.
//
// Steer. `fork` under a live worker's name delivers the verbose intent to that
// worker's own session (`session.steer` while it streams, `session.prompt` when
// idle), and the receipt says `steered: true` only when that delivery returned.

import { objectSchema, STRING, guard, present, textResult, receiptResult } from "./tool-schema.mjs";
import { forkRequest } from "../compose/program/role-brief.mjs";
import { SCOPE_PROPERTY } from "./program-tools.mjs";
import { CAPABILITY } from "./host-binding.mjs";

export class TaskPort {
  #lanes;
  #sleep;
  #agentFor;
  #snapshot;
  #depth;
  #sessions;

  // `lanes` is the worker roster (`lane.mjs` `LaneTable`); `agentFor(role)`
  // maps a worker's declared role to the host agent definition that runs it —
  // the argument is a ROLE, never a worker name. `snapshot()` reads
  // `ctx.getAsyncJobSnapshot()`; the default reads nothing, so a port built
  // without a host simply finds no finished job.
  constructor({ lanes, sleep, agentFor, snapshot = () => null, depth = null, sessions = null }) {
    this.#lanes = lanes;
    this.#sleep = sleep;
    this.#agentFor = agentFor;
    this.#snapshot = snapshot;
    // The live sessions of the workers this process started, by name. The
    // session's `LaneTable` rebuild adopts a journal `RUNNING` worker through
    // this map, so a reload keeps waiting on the real turn instead of on a
    // `settle` that belongs to the process which ended. The map is shared with
    // the surface by reference; a fork records into it as soon as the lane
    // starts.
    this.#sessions = sessions instanceof Map ? sessions : null;
    // The depth a child of the CALLING session must carry: that session's own
    // depth plus one (`surface.mjs` `#laneDepth`). `null` leaves the table's
    // own default in place, which is what a table built for one fixed depth
    // expects. This is a fact about nesting, never a cap — see `lane.mjs`.
    this.#depth = typeof depth === "function" ? depth : null;
  }

  // The workers this roster still owes, in the shape the host reports running
  // work: `{ id, status, agentId }`. A restored worker the journal says is
  // running belongs here, because from `join`'s point of view it IS running —
  // the process that would have finished it may already be gone.
  //
  // It is read by the surface at bind time to hand a live worker back to its
  // roster (`LaneTable.adopt`), so a reloaded session waits on the real turn
  // instead of on a promise nobody will settle.
  get runningWorkers() {
    if (this.#lanes === null || this.#lanes === undefined) return [];
    const restored = typeof this.#lanes.restoredEntries === "function" ? this.#lanes.restoredEntries() : [];
    const out = [];
    for (const lane of this.#lanes.all()) {
      if (lane.settled) continue;
      out.push({ id: lane.name, status: "running", agentId: lane.agentId });
    }
    for (const entry of restored) {
      out.push({ id: entry.name, status: "running", agentId: entry.agentId ?? entry.name });
    }
    return out;
  }

  // `fork(name, verboseIntent, role)`. Returns `{ name, steered, started }`. A
  // steer that did not reach a live worker, and a spawn that failed, are both
  // reported with a reason; the receipt never claims a delivery that did not
  // happen.
  //
  // The third argument is compose's resolved role, read from the intent's own
  // declaration (§2.1, §4.1). This port never derives it from the worker's name:
  // a worker called `devops` with a coder intent is a coder.
  async fork(name, verboseIntent, role) {
    // A session that has not bound yet has no roster. That is a fact about this
    // call, not a refusal to make up a worker: the answer names it.
    if (this.#lanes === null || this.#lanes === undefined) {
      return { name, steered: false, started: false, reason: "NO_SESSION" };
    }
    if (typeof name !== "string" || name.length === 0) {
      return { name, steered: false, started: false, reason: "INVALID_NAME" };
    }
    if (typeof verboseIntent !== "string" || verboseIntent.trim().length === 0) {
      return { name, steered: false, started: false, reason: "INVALID_VERBOSE_INTENT" };
    }
    // The identity comes from the intent's declaration, never from the name and
    // never from a default (§2.1, §4.1). A caller that did not resolve one — a
    // bare port used without compose — gets the same refusal compose would give.
    if (role !== "coder" && role !== "devops") {
      try {
        role = forkRequest(name, verboseIntent).role;
      } catch (error) {
        return { name, steered: false, started: false, reason: error.details?.reason ?? "UNDECLARED_ROLE" };
      }
    }
    // A cancelled subtree accepts no new work (§1.1 item 2): the operator
    // stopped this branch, and a `fork` that started a child now would be the
    // background token burn the cancellation exists to prevent.
    if (this.#lanes.cancelled !== null && this.#lanes.cancelled !== undefined) {
      return { name, steered: false, started: false, reason: "SESSION_CANCELLED" };
    }
    // A live worker of the same name is steered, not duplicated: the intent goes
    // into its own session through `write agent://<id>`.
    if (this.#lanes.live(name) !== null) {
      const steered = await this.#lanes.steer(name, verboseIntent);
      return steered
        ? { name, steered: true, started: false }
        : { name, steered: false, started: false, reason: CAPABILITY.STEER_UNDELIVERED };
    }
    const depth = this.#depth === null ? null : this.#depth();
    const started = await this.#lanes.start({
      name,
      role: this.#agentFor(role),
      verboseIntent,
      ...(typeof depth === "number" ? { depth } : {}),
    });
    if (started.ok === true) {
      this.#sessions?.set(name, started.lane.session);
      // The lane is in this roster from the moment it started. A `join` of the
      // SAME session therefore always has something to wait on — there is no
      // window between the fork receipt and the lane's first event in which the
      // caller is told there is nothing to join.
      const known = this.#lanes.names();
      return known.includes(name)
        ? { name, steered: false, started: true }
        : { name, steered: false, started: false, reason: "ROSTER_MISSING_LANE" };
    }
    return {
      name,
      steered: false,
      started: false,
      reason: started.reason,
      ...(started.detail === undefined ? {} : { detail: started.detail }),
    };
  }

  // `join()`: wait for the first lane of THIS session's roster to hand over its
  // report. It never waits for every worker, and it never waits past the first
  // finished one.
  //
  // The answer names the roster whenever there is nothing to join. A caller
  // that just forked and then joined in the same turn must never be told
  // "nothing" while its own worker is running: an empty `roster` says the fork
  // landed in another table, and a listed name says that worker already handed
  // its report over (each report is taken once).
  //
  // A finished worker answers the record itself (`{ ok: true, name, text, … }`);
  // there is no wrapper, and no `record` field to read `null` from.
  //
  // ## The three ways this must not answer early
  //
  //   - a worker whose turn is live: `join` waits on the roster's own arrival
  //     signal, which fires when a turn ends;
  //   - a worker that finished while nobody listened (a reload, a process
  //     restart): its result is in the journal, and the roster was rebuilt from
  //     it (`worker-roster.mjs`), so it is claimed instead of being forgotten;
  //   - a wait that answers without the roster changing: the host said nothing
  //     new, so `join` waits again rather than spinning or giving up.
  //
  // ## Why a disposed roster ends the wait
  //
  // The wait is armed on a turn ending, and a roster that has been torn down
  // never ends another one. A session or adapter disposed while a `join` was
  // pending would otherwise leave that call pending for the life of the
  // process — the same hang a host would suffer, not a test artefact. So the
  // wait ends with a refusal that NAMES the disposal. It is deliberately not
  // `NOTHING_TO_JOIN`: that refusal means "this roster holds no worker to
  // wait for", and a disposed roster is a different fact — reporting it as an
  // empty roster would invite the caller to fork into a table that is gone.
  //
  // ## Why a replaced roster ends the wait
  //
  // A reload swaps the session's `LaneTable` and DETACHES the old one
  // (`surface.mjs` `#newRoster` → `LaneTable.detach`): its lanes stop listening,
  // so a wait armed on it is armed on a signal that will never fire. It ends
  // with the same `SESSION_DISPOSED` refusal a teardown gives — this table's
  // work is gone, and a new `join` on the session's current table collects the
  // workers. Never `NOTHING_TO_JOIN`: that would be the lie this port exists to
  // prevent.
  async join() {
    if (this.#lanes === null || this.#lanes === undefined) return { ok: false, reason: "NO_SESSION" };
    for (;;) {
      // A finished result is claimed first: it exists now, so waiting for a
      // different worker would be the wrong answer to "join one worker".
      const ready = this.#lanes.takeReady();
      if (ready !== null) return ready;
      // A disposed roster owes nothing that can still arrive, whatever it holds.
      if (this.#lanes.disposed === true) return this.#disposedRefusal();
      // A detached roster owes nothing to THIS call: its lanes no longer hear
      // the turn ends that now reach the table which replaced it.
      if (this.#lanes.detached === true) return this.#disposedRefusal();
      // A cancelled subtree (root §1.1 item 2) never ends another turn. Every
      // result it already produced was claimed above, so the honest answer now
      // names the cancellation instead of waiting for work the operator stopped.
      if (this.#lanes.cancelled !== null && this.#lanes.cancelled !== undefined) return this.#cancelledRefusal();
      // Nothing claimable, and nothing owed: the honest "nothing to collect".
      // The refusal says what the roster holds, so an empty `roster` (a fork in
      // another table) is distinguishable from a listed name (already
      // collected).
      if (this.#lanes.owed() !== true) {
        const names = typeof this.#lanes.names === "function" ? this.#lanes.names() : [];
        return {
          ok: false,
          reason: "NOTHING_TO_JOIN",
          detail:
            names.length === 0 ? "this session's roster holds no worker" : `no pending worker; roster: ${names.join(", ")}`,
          roster: names,
        };
      }
      // Work is owed but not claimable yet: a live turn, or a restored worker
      // this process cannot drive. Wait for the roster to change, and re-check.
      // A host that answers without a change must not turn this into a spin:
      // `waitForChange` yields one macrotask before it reports the same state.
      await this.#lanes.waitForChange();
      // The wait can be ended BY the teardown that removed the turn it was
      // armed for, so the fact is read again before the roster is re-examined.
      if (this.#lanes.disposed === true) return this.#disposedRefusal();
      if (this.#lanes.detached === true) return this.#disposedRefusal();
    }
  }

  // What a `join` waiting on a torn-down roster is told. The roster is still
  // named, so a caller can see which workers it was waiting for when the
  // session went away.
  #disposedRefusal() {
    // The roster was emptied by the teardown, so its live names are gone. The
    // names it held at that moment are the useful fact; a table that cannot
    // report them falls back to whatever it still lists.
    const held = this.#lanes.disposedNames;
    const names = Array.isArray(held) ? held : typeof this.#lanes.names === "function" ? this.#lanes.names() : [];
    return {
      ok: false,
      reason: "SESSION_DISPOSED",
      detail: "this session was disposed while join was waiting, so no worker can report to it",
      roster: names,
    };
  }

  // What a `join` on a cancelled subtree is told. Its unclaimed results were
  // handed over first (the loop claims before it reads this), so nothing the
  // workers really produced is lost; what remains is the fact that the operator
  // stopped this branch and no further turn will arrive.
  #cancelledRefusal() {
    const names = typeof this.#lanes.names === "function" ? this.#lanes.names() : [];
    return {
      ok: false,
      reason: "SESSION_CANCELLED",
      detail: "the operator interrupted this session; its descendant workers were aborted and no further turn will arrive",
      roster: names,
    };
  }

}

// `port` is `(ctx) => TaskPort`: the port belongs to the session the call runs
// in, so a worker's `fork` fills the worker's roster and the Manager's `join`
// reads the Manager's. A port resolved once at factory time would give every
// session the factory's own roster.
export const forkTool = ({ port }) => ({
  name: "fork",
  label: "Fork Worker",
  description:
    "Start a worker and return at once, or steer a live worker of the same name. " +
    "Two arguments: name and verboseIntent. The verboseIntent must declare the identity once, as `role: coder` " +
    "or `role: devops`; an intent that names neither, or both, is refused. " +
    "Receipt: { name, steered, started }. steered:true means the verboseIntent reached the existing worker; " +
    "steered:false with started:true means a new worker started.",
  // No `role` parameter: §4.1 gives `fork` two arguments, and the identity is
  // read from the verboseIntent. `role` in params is accepted from compose's
  // port only.
  parameters: objectSchema({ name: STRING, verboseIntent: STRING, scope: SCOPE_PROPERTY }),
  async execute(_id, params, _signal, _onUpdate, ctx) {
    return await guard(async () => {
      const worker = typeof port === "function" ? port(ctx) : port;
      if (worker === null || worker === undefined) {
        const refused = { name: params.name, steered: false, started: false, reason: "NO_SESSION" };
        return textResult(present(refused), refused);
      }
      const receipt = await worker.fork(params.name, params.verboseIntent, params.role);
      return textResult(present(receipt), receipt);
    });
  },
});

export const joinTool = ({ port }) => ({
  name: "join",
  label: "Join One Worker",
  description: "Wait for the first worker to finish its turn and return its report. Takes no argument besides `scope`.",
  parameters: objectSchema({ scope: SCOPE_PROPERTY }),
  async execute(_id, _params, _signal, _onUpdate, ctx) {
    return await guard(async () => {
      const worker = typeof port === "function" ? port(ctx) : port;
      if (worker === null || worker === undefined) {
        const refused = { ok: false, reason: "NO_SESSION" };
        return textResult(present(refused), refused);
      }
      const report = await worker.join();
      return textResult(present(report), report);
    });
  },
});

// The two exempt snapshot members of Scope (spec §6.2): `negotiate` and
// `askPreviousScope`. They are HOST TOOLS, not program members, because the
// model must be able to ask them between program runs — a program is one tool
// call, and the budget it is about to spend is decided before that call.
//
// Both are exempt: `ScopeHooks#ask` answers without an annotation, without
// spending retention and without a reset when the budget is spent (§6.2). The
// answer is synchronous and comes from the newest committed facts; while a call
// is still settling it is the refusal `SCOPE_SNAPSHOT_BUSY`, which the model
// reads as "ask again", never as a budget.
//
// Their schema has NO `scope` property on purpose: an exempt tool neither needs
// the annotation nor spends the budget, and offering the field would teach the
// model to annotate a call the book never books. The description says so in one
// line.
const scopeSnapshotTool = ({ member, label, description, port }) => ({
  name: member,
  label,
  description: description + " Exempt: no `scope` annotation is needed or accepted.",
  parameters: objectSchema({}),
  async execute(_id, _params, _signal, _onUpdate, ctx) {
    return await guard(async () => {
      const ask = typeof port === "function" ? port(ctx) : port;
      if (typeof ask !== "function") {
        return receiptResult({ ok: false, code: "NO_SESSION", member });
      }
      return receiptResult(ask(member));
    });
  },
});

// `port` is `(ctx) => (member) => snapshot`: resolved per call, like `fork`, so
// the answer describes the session the model is working in.
export const negotiateTool = ({ port }) =>
  scopeSnapshotTool({
    member: "negotiate",
    label: "Scope Snapshot",
    description:
      "Ask the Scope book what this session has left: goal, budget, remaining retention, spin count. " +
      "Exempt from retention and synchronous; never parks.",
    port,
  });

export const askPreviousScopeTool = ({ port }) =>
  scopeSnapshotTool({
    member: "askPreviousScope",
    label: "Previous Scope",
    description:
      "Ask which Scope boundary this session currently stands behind: its goal, budget and the turn that opened it. " +
      "Exempt from retention and synchronous; never parks.",
    port,
  });
