// Fission: one worker identity becomes n lanes (spec §4.2).
//
// `prompts` is a string array, at least two items, none blank. Lane i starts
// from the owner's last delivered view plus its own prompt. The whole group is
// admitted or refused as one; any failure after admission aborts the lanes
// already created, so no partial group and no replacement group is left.
//
// Lane order, merge order (0..n−1) and the terminal lane (n−1) are topology,
// not arrival order. `ConvergenceReady` is the conjunction of two independent
// facts: every lane delivered work, and every in-flight completion captured
// before fission reached every lane. Only the terminal lane may announce the
// end, and only after `ConvergenceReady`.
//
// States: admitted -> started -> replaced -> ready -> converged | failed.
// `converged` and `failed` are terminal and one-shot; repeating the same value
// is idempotent, a different value is `TERMINAL_ALREADY_SET`.

import { invalidArgument, REJECTIONS } from "../../core/errors.mjs";
import { COMPOSE_CODES, ComposeError } from "../errors.mjs";
import { expectArray } from "../../core/util.mjs";

export const GROUP_STATES = Object.freeze({
  ADMITTED: "admitted",
  STARTED: "started",
  REPLACED: "replaced",
  READY: "ready",
  CONVERGED: "converged",
  FAILED: "failed",
});

export const TERMINAL_GROUP_STATES = Object.freeze([GROUP_STATES.CONVERGED, GROUP_STATES.FAILED]);

// A lane: its index in the group, a stable id, the prompt it starts from, and
// how many lanes the group has. Spec §4.2 step 3 puts `lane_count` in every
// lane's start context, so it is a property of the lane itself: the host never
// counts lanes to recover a fact the group already fixed.
export const laneOf = (index, laneId, prompt, laneCount) =>
  Object.freeze({ index, laneId, prompt, laneCount });

// Every lane of one group. `laneCount` is the length of the whole list, never
// an index: a host that read `index + 1` would put a different number in each
// lane's context and contradict the framing it prints.
export const lanesOf = (groupId, prompts) =>
  prompts.map((prompt, index) => laneOf(index, `${groupId}#${index}`, prompt, prompts.length));

const refusal = (rejection, extra = {}) => ({ ok: false, rejection, ...extra });

// The identity a broadcast completion is claimed by. A host may name it with
// `key`, `name` or `id`; the group normalizes once, at `broadcast`, so the
// claim and the convergence test always compare the same value.
const completionKey = (completion) =>
  completion?.key ?? completion?.name ?? completion?.id ?? completion;

// Validate the prompt list before anything is created.
export const validatePrompts = (prompts) => {
  expectArray("prompts", prompts);
  if (prompts.length < 2) return refusal(REJECTIONS.TooFewLanes);
  for (let index = 0; index < prompts.length; index += 1) {
    const prompt = prompts[index];
    if (typeof prompt !== "string" || prompt.trim().length === 0) {
      return refusal(REJECTIONS.EmptyLanePrompt, { index });
    }
  }
  return { ok: true };
};

// The port acknowledges a step with a receipt. A receipt that says `ok: false`
// is a failure even though the call returned: the step did not happen.
export const portRefused = (receipt) =>
  receipt !== null && typeof receipt === "object" && receipt.ok === false;

export const portFailure = (step, receipt) =>
  Object.assign(new Error(`${step} was refused: ${receipt.reason ?? receipt.rejection ?? "no reason"}`), {
    code: receipt.reason ?? receipt.rejection ?? "PORT_REFUSED",
    step,
    receipt,
  });

// The owner's last delivered view: text, or nothing. Anything else is a host
// bug; refusing it here keeps a broken view out of every lane's start text.
export const asOwnerView = (view) => {
  if (view === null || view === undefined) return null;
  if (typeof view !== "string") {
    throw invalidArgument("ownerView must return a string or null", { view });
  }
  return view;
};

// One fission group. It owns facts only; the port owns sessions.
export class LaneGroup {
  #groupId;
  #owner;
  #lanes;
  #state;
  #delivered;
  // Each lane's own report, kept from the moment it delivered, so the
  // announcement always names the TAKEOVER lane's result whatever order the
  // lanes finish in.
  #results;
  #broadcasts;
  #broadcasted;
  #terminal;

  constructor({ groupId, owner, lanes }) {
    this.#groupId = groupId;
    this.#owner = owner;
    this.#lanes = lanes;
    this.#state = GROUP_STATES.ADMITTED;
    this.#delivered = new Set();
    this.#results = new Map();
    this.#broadcasts = [];
    this.#broadcasted = new Map();
    this.#terminal = null;
  }

  get groupId() {
    return this.#groupId;
  }

  get owner() {
    return this.#owner;
  }

  get lanes() {
    return [...this.#lanes];
  }

  get state() {
    return this.#state;
  }

  get laneCount() {
    return this.#lanes.length;
  }

  get terminalLane() {
    return this.#lanes.length - 1;
  }

  // The group's state as §4.2 step 8 names it. `ConvergenceReady` is not a
  // separate stored flag: it is the conjunction itself, so the state a caller
  // reads can never disagree with the two facts it is made of.
  get phase() {
    if (this.#terminal !== null) return this.#terminal.state;
    if (this.#state === GROUP_STATES.REPLACED && this.convergenceReady()) return GROUP_STATES.READY;
    return this.#state;
  }

  // Whether this lane already handed its work in. A host that settles a lane
  // twice must not deliver twice: a second delivery is the same fact.
  hasDelivered(index) {
    return this.#delivered.has(index);
  }

  // Merge order is always 0..n−1, independent of arrival.
  mergeOrder() {
    return this.#lanes.map((lane) => lane.index);
  }

  // The next OTHER lane after `index` that has not delivered its work, going
  // round the ring; null when every other lane already delivered. The caller
  // itself is never its own successor.
  successorOf(index) {
    if (!Number.isInteger(index) || index < 0 || index >= this.#lanes.length) {
      throw invalidArgument(`no lane ${index} in group ${this.#groupId}`);
    }
    const open = (candidate) => !this.#delivered.has(candidate);
    for (let step = 1; step < this.#lanes.length; step += 1) {
      const candidate = (index + step) % this.#lanes.length;
      if (open(candidate)) return candidate;
    }
    return null;
  }

  start() {
    this.#move(GROUP_STATES.STARTED);
    return this;
  }

  replace() {
    this.#move(GROUP_STATES.REPLACED);
    return this;
  }

  // A lane handed its work in. Single claim per lane. `result` is that lane's
  // own report; it is remembered so a later delivery can announce with the
  // takeover lane's report (see `announcement`).
  deliver(index, result = null) {
    this.#requireLane(index);
    // A terminal group takes no new work: the merge order was decided by the
    // terminal write, and a late delivery would make `ConvergenceReady` depend
    // on when the host reported it rather than on the topology.
    this.#refuseAfterTerminal("deliver");
    this.#delivered.add(index);
    if (!this.#results.has(index)) this.#results.set(index, result ?? null);
    this.#refresh();
    return this.convergenceReady();
  }

  // What the takeover lane would announce if the group is ready: its own stored
  // report, or null when the takeover lane has not delivered.
  //
  // Convergence is a conjunction, not an order: whichever delivery completes it
  // — a sibling's after the takeover lane already delivered, a broadcast's
  // last claim — the announcement is the takeover lane's report and nobody
  // else's. `null` means the group is not ready or the takeover lane has
  // nothing to announce yet.
  announcement() {
    if (this.#terminal !== null) return null;
    // The takeover lane announces only after the group reached `ready`: the
    // state is the conjunction, and a group still being built (admitted or
    // started) has not replaced its present yet.
    if (this.phase !== GROUP_STATES.READY) return null;
    if (!this.#delivered.has(this.terminalLane)) return null;
    return { lane: this.terminalLane, result: this.#results.get(this.terminalLane) ?? null };
  }

  // A completion that was already in flight when fission happened. Each lane
  // claims it once; repeated delivery to the same lane does nothing.
  broadcast(completion) {
    this.#refuseAfterTerminal("broadcast");
    this.#broadcasts.push(completionKey(completion));
    this.#refresh();
    return this.convergenceReady();
  }

  claimBroadcast(index, completionKey) {
    this.#requireLane(index);
    this.#refuseAfterTerminal("claim");
    const claimed = this.#broadcasted.get(index) ?? new Set();
    if (claimed.has(completionKey)) return false;
    claimed.add(completionKey);
    this.#broadcasted.set(index, claimed);
    this.#refresh();
    return true;
  }

  // Both facts must hold: every lane delivered, and every broadcast reached
  // every lane.
  convergenceReady() {
    if (this.#delivered.size !== this.#lanes.length) return false;
    for (let index = 0; index < this.#lanes.length; index += 1) {
      const claimed = this.#broadcasted.get(index) ?? new Set();
      for (const key of this.#broadcasts) if (!claimed.has(key)) return false;
    }
    return true;
  }

  // Only the terminal lane announces, and only once `ConvergenceReady` holds.
  //
  // The terminal lane's own delivery is implied, not required: spec §4.2 makes
  // the announcing lane the one that closes the merge, and a host that lets it
  // announce before reporting its own delivery would otherwise lose the group
  // to `INVALID_ARGUMENT` for a fact already true. `ConvergenceReady` still
  // needs every other lane and every broadcast.
  converge({ lane, result = null }) {
    if (lane !== this.terminalLane) {
      throw invalidArgument(`lane ${lane} may not announce group ${this.#groupId}`);
    }
    if (this.#terminal !== null) {
      if (this.#terminal.state === GROUP_STATES.CONVERGED && this.#terminal.result === result) {
        return this.#terminalReceipt();
      }
      throw new ComposeError(
        COMPOSE_CODES.TERMINAL_ALREADY_SET,
        `group ${this.#groupId} is ${this.#state}`,
        { groupId: this.#groupId },
      );
    }
    this.#delivered.add(lane);
    if (!this.#results.has(lane)) this.#results.set(lane, result ?? null);
    if (!this.convergenceReady()) {
      // The implied delivery must not outlive a refused announcement: the lane
      // said nothing, so the group must not remember that it did.
      this.#delivered.delete(lane);
      this.#results.delete(lane);
      throw invalidArgument(`group ${this.#groupId} is not ConvergenceReady`);
    }
    this.#writeTerminal(GROUP_STATES.CONVERGED, result);
    return { ok: true, groupId: this.#groupId, lanes: this.lanes, result };
  }

  fail(reason) {
    this.#writeTerminal(GROUP_STATES.FAILED, reason);
    return { ok: true, groupId: this.#groupId, lanes: this.lanes, result: reason };
  }

  #requireLane(index) {
    if (!Number.isInteger(index) || index < 0 || index >= this.#lanes.length) {
      throw invalidArgument(`no lane ${index} in group ${this.#groupId}`);
    }
  }

  #refuseAfterTerminal(what) {
    if (this.#terminal === null) return;
    throw new ComposeError(
      COMPOSE_CODES.TERMINAL_ALREADY_SET,
      `group ${this.#groupId} is ${this.#state}; ${what} is closed`,
      { groupId: this.#groupId },
    );
  }

  #terminalReceipt() {
    return {
      ok: true,
      groupId: this.#groupId,
      lanes: this.lanes,
      result: this.#terminal.result,
      terminal: this.#terminal.state,
    };
  }

  #writeTerminal(state, result) {
    if (this.#terminal !== null) {
      if (this.#terminal.state === state && this.#terminal.result === result) return;
      throw new ComposeError(
        COMPOSE_CODES.TERMINAL_ALREADY_SET,
        `group ${this.#groupId} is ${this.#terminal.state}`,
        { groupId: this.#groupId },
      );
    }
    this.#terminal = { state, result };
    this.#state = state;
  }

  #move(next) {
    // Reaching a later state after a terminal one is a repeat, not a rewrite.
    if (this.#terminal !== null && next === this.#state) return this.#state;
    if (this.#terminal !== null) {
      throw new ComposeError(
        COMPOSE_CODES.TERMINAL_ALREADY_SET,
        `group ${this.#groupId} is ${this.#state}`,
        { groupId: this.#groupId },
      );
    }
    const order = [
      GROUP_STATES.ADMITTED,
      GROUP_STATES.STARTED,
      GROUP_STATES.REPLACED,
      GROUP_STATES.READY,
    ];
    if (order.indexOf(next) !== order.indexOf(this.#state) + 1) {
      throw invalidArgument(`group ${this.#groupId} cannot move ${this.#state} -> ${next}`);
    }
    this.#state = next;
  }

  #refresh() {
    if (this.#state === GROUP_STATES.REPLACED && this.convergenceReady()) {
      this.#state = GROUP_STATES.READY;
    }
  }
}
