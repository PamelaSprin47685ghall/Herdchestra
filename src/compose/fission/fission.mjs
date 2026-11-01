// `fission(prompts)` (spec §4.2).
//
// The member refuses early and creates nothing when a precondition fails. The
// whole group is admitted or refused as one: the group record is committed
// FIRST, the lanes are started after it, and any failure after admission rolls
// the whole group back, so no partial group and no replacement group remains.
//
// Preconditions (refusals, never thrown):
//   InvalidOrigin        the caller is not a worker started by `fork`
//   AlreadyActive        the caller already has a live group
//   TooFewLanes          fewer than two prompts
//   EmptyLanePrompt      a blank prompt, with its index
//   CapacityExceeded     the host has no room for the whole group
//   ConflictingGroup     another group is already being built
//
// A host port step that answers `ok: false` did not happen. It fails the group
// exactly like a thrown error: the whole group is rolled back and the owner
// keeps its old present, so it may try again.

import { CoreError, CODES, invalidArgument, REJECTIONS } from "../../core/errors.mjs";
import {
  GROUP_STATES,
  LaneGroup,
  asOwnerView,
  lanesOf,
  portFailure,
  portRefused,
  validatePrompts,
} from "./group.mjs";

const refusal = (rejection, extra = {}) => ({ ok: false, rejection, ...extra });

// A port this orchestrator does not need (an older host that only implements
// the six step methods) answers with a refusal instead of a crash, so one
// missing convenience never turns a whole group into an exception.
const step = (port, name) => (typeof port?.[name] === "function" ? port[name].bind(port) : null);

// How many settled turns a session with no group remembers for a future group's
// step-7 broadcast. A group's completions are the turns that settled just before
// it, and a session that never fissions must not keep every turn it ever ran.
const PENDING_LIMIT = 256;

const isTerminalGroup = (group) =>
  group.phase === GROUP_STATES.CONVERGED || group.phase === GROUP_STATES.FAILED;

// There is no time limit on a build step. Time in this system comes from one
// place only: the request deadline fixed from `intent` at admission (spec §3.3).
// A step that never answers is a host that never answers; the composition layer
// reports what the host reports and does not invent a second clock.
//
// `origins(name)` answers whether a program is a fork-started worker.
// `capacity(laneCount)` answers whether the host can take the whole group.
export class Fission {
  #port;
  #origins;
  #capacity;
  #groups;
  #building;
  #buildingGroups;
  #ids;
  #sequence;
  // The settled turns no group has taken yet, in settlement order (step 7).
  // They are captured HERE, not when the lanes settle, because "in flight when
  // fission happened" is a fact about the moment of the call.
  #pending;

  // `capacity(laneCount)` is the host's real answer. A caller that supplies none
  // gets a function that answers `false`: a host that has not measured its room
  // has not said it has any, and admitting a group on an assumed `true` would
  // build lanes nobody counted.
  constructor({ port, origins, capacity = () => false }) {
    this.#port = port;
    this.#origins = origins;
    this.#capacity = capacity;
    this.#groups = new Map();
    this.#building = new Set();
    // The group a build has created but not yet committed. A one-step host's
    // lane can finish inside `createLane`/`startLane`, before the group reaches
    // `#groups`; `settle`/`deliver`/`#take` read this map so that delivery is
    // not lost.
    this.#buildingGroups = new Map();
    // Every group id this instance has handed out, so a second group of one
    // owner never reuses an id a host may still hold.
    this.#ids = new Set();
    this.#sequence = 0;
    this.#pending = [];
  }

  // The group an owner currently has, if any.
  groupOf(owner) {
    return this.#groups.get(owner) ?? null;
  }

  // The groups that are not terminal, in the order they were admitted, plus
  // any group still being built. A completion captured before fission belongs
  // to every one of them.
  #liveGroups() {
    const groups = [...this.#buildingGroups.values()];
    for (const group of this.#groups.values()) {
      if (groups.includes(group)) continue;
      if (isTerminalGroup(group)) continue;
      groups.push(group);
    }
    return groups;
  }

  // One settled turn that no group has taken, as it reaches a group.
  //
  // A lane's settlement is its DELIVERY and its turn's completion is a step-7
  // broadcast: a lane's own report is not news to the siblings. The host names
  // the lane it belongs to, and that name is the lane id of a group this
  // instance built; anything else is a `fork` worker's turn.
  #take(completion) {
    for (const group of this.#liveGroups()) {
      const lane = group.lanes.find((candidate) => candidate.laneId === completion?.name);
      if (lane === undefined) continue;
      const index = lane.index;
      const ready = group.deliver(index);
      // §4.2 step 10: the takeover lane announces, exactly as a settlement
      // does, and the announced result is that lane's own stored report. The
      // conjunction may be completed by any lane's delivery or by a broadcast
      // claim, so the announcement is asked for, never inferred from the index.
      const announcement = group.announcement();
      if (announcement !== null) {
        const receipt = group.converge({ lane: announcement.lane, result: announcement.result });
        return { groupId: group.groupId, owner: group.owner, lane: index, converged: receipt };
      }
      return { groupId: group.groupId, owner: group.owner, lane: index, delivered: true, ready };
    }
    this.#pending.push(completion);
    return null;
  }

  // A turn that settled while this instance held no group. The next group takes
  // it as an in-flight completion (step 7); nothing else ever will, so the list
  // is trimmed to the turn ids that settled most recently — a session that
  // never fissions must not grow a queue of every worker turn it ever ran.
  settled(completion) {
    if (completion === null || typeof completion !== "object") return null;
    const taken = this.#take(completion);
    if (taken !== null) return taken;
    const key = String(completion.name);
    this.#pending = this.#pending.filter((entry) => String(entry.name) !== key);
    this.#pending.push(completion);
    if (this.#pending.length > PENDING_LIMIT) this.#pending.splice(0, this.#pending.length - PENDING_LIMIT);
    return null;
  }

  // Every group this instance holds. The host rebuilds its lane index from
  // here: a roster rebuilt over a running lane must find the session the group
  // created, and the group is the only owner of that record.
  groups() {
    return [...this.#groups.values()];
  }

  // The name this group is recorded under. It is the SESSION's identity, not the
  // name of the program that called `fission` — a program is named by its
  // tool-call id, and a group id built from that names nothing a caller can
  // trace back to a session. The port knows the session; this asks it.
  //
  // A port that cannot name its session gets `null`, which `origins` refuses as
  // `InvalidOrigin`. Returning the program's name instead would let a group be
  // recorded under a tool-call id the session never answers to.
  ownerName() {
    const port = this.#port;
    if (port !== null && typeof port === "object" && typeof port.ownerName === "function") {
      const name = port.ownerName();
      return typeof name === "string" && name.length > 0 ? name : null;
    }
    return null;
  }

  async fission(owner, prompts) {
    if (!this.#origins(owner)) return refusal(REJECTIONS.InvalidOrigin);
    const current = this.#groups.get(owner);
    if (current !== undefined && !isTerminalGroup(current)) {
      // Spec §4.2: no recursive fission and no replacement group while a group
      // is ACTIVE. A converged/failed group is not active — step 10 restored
      // the owner to a single present — so the owner may fission again; the
      // finished record stays addressable until the new group replaces it.
      return refusal(REJECTIONS.AlreadyActive);
    }
    if (this.#building.has(owner)) return refusal(REJECTIONS.ConflictingGroup);
    const valid = validatePrompts(prompts);
    if (!valid.ok) return valid;
    if (!this.#capacity(prompts.length)) return refusal(REJECTIONS.CapacityExceeded);

    this.#building.add(owner);
    this.#sequence += 1;
    const groupId = `${owner}:${prompts.length}:${this.#sequence}`;
    if (this.#ids.has(groupId)) {
      // Unreachable through `#sequence`, but a caller that reuses an id would
      // otherwise let two groups share one persisted record.
      this.#building.delete(owner);
      return refusal(REJECTIONS.DuplicateOwnerActive);
    }
    this.#ids.add(groupId);
    // Every lane carries the group's lane count (spec §4.2 step 3), so each
    // host prints the same number and none of them counts lanes to find it.
    const lanes = lanesOf(groupId, prompts);
    const group = new LaneGroup({ groupId, owner, lanes });
    // The group becomes visible to `settle`/`deliver`/`#take` BEFORE any host
    // step can run a lane. A one-step host's lane IS its first turn, so without
    // this its settlement would find no group and its delivery would be lost.
    // A successful build moves it into `#groups`; a rollback deletes it.
    this.#buildingGroups.set(owner, group);
    // The completions this group was handed (step 7). They are the turns that
    // settled before it existed; they reach every lane after the group is live.
    const captured = this.#pending;
    this.#pending = [];
    // Which steps really happened, so the rollback undoes exactly those.
    let built = 0;
    let committed = false;
    try {
      // The owner's last delivered view goes to every lane; an empty view is
      // not a refusal.
      const view = asOwnerView(await this.#port.ownerView(owner));
      // Steps 3 and 4, in the order that makes a crash survivable.
      //
      // A host that can build a lane without running it (`buildLane`) builds
      // every lane INERT first; the group record is then committed, and only
      // then does any lane start. A host whose lane IS its first turn
      // (`createLane`) cannot be held back, and its step is the older one-step
      // order: build-and-start, commit, confirm.
      const buildLane = step(this.#port, "buildLane");
      const createLane = buildLane === null ? step(this.#port, "createLane") : null;
      if (buildLane === null && createLane === null) {
        throw Object.assign(new Error("the host has no way to build a fission lane"), { code: "LANE_BUILDER_MISSING" });
      }
      for (const lane of lanes) {
        const created = await (buildLane ?? createLane)({ ...lane, groupId, owner, view });
        if (portRefused(created)) throw portFailure(buildLane === null ? "createLane" : "buildLane", created);
        built += 1;
      }
      // Spec §4.2 step 4: the group's creation is committed BEFORE any lane
      // runs. A commit that is not durable is a refusal: step 4 is what makes a
      // crash survivable, and a group without it must not start.
      const committedReceipt = await this.#port.persistGroup({ groupId, owner, lanes, view });
      if (portRefused(committedReceipt)) throw portFailure("persistGroup", committedReceipt);
      committed = true;
      // Step 5: every lane starts, in order. A start that fails rolls the whole
      // committed group back, record included. On a one-step host the lane
      // already ran, and this step only confirms that it exists.
      for (const lane of lanes) {
        const started = await this.#port.startLane({ ...lane, groupId, owner, view });
        if (portRefused(started)) throw portFailure("startLane", started);
      }
      group.start();
      // Step 6: the old physical present is silenced. A host that has to defer
      // the abort to the end of the turn that asked for it still reports
      // `ok: true`; one that cannot stop the present at all refuses here and
      // the group is rolled back rather than left half-replaced.
      const replaced = await this.#port.replacePresent({ groupId, owner, lanes });
      if (portRefused(replaced)) throw portFailure("replacePresent", replaced);
      group.replace();
      // A lane may have delivered during the build (a synchronous host). The
      // conjunction is complete only now, after `replace`, so the takeover lane
      // announces here exactly as it would from a settlement.
      const announcement = group.announcement();
      if (announcement !== null) {
        group.converge({ lane: announcement.lane, result: announcement.result });
      }
      // Step 7: every completion captured before fission reaches every lane,
      // once per lane (single-flight claim). It is delivered after the group is
      // live, because a lane can only take a broadcast of a group that exists.
      // A group that already converged during the build took every claim it
      // needed (condition B is part of the conjunction), so re-broadcasting
      // would be a write to a terminal group.
      if (!isTerminalGroup(group)) {
        for (const completion of captured) group.broadcast(completion);
        // The broadcasts may be what completes condition B for lanes that
        // already delivered during the build. The takeover lane announces here
        // for the same reason `settle` does after every delivery.
        const settledAnnouncement = group.announcement();
        if (settledAnnouncement !== null) {
          group.converge({ lane: settledAnnouncement.lane, result: settledAnnouncement.result });
        }
      }
    } catch (error) {
      // The build did not happen. The completions it captured are still facts
      // no group has delivered, so they go back to the FRONT of the pending
      // queue (deduped, newest name wins) for the next group to inherit.
      // Dropping them would silently compute condition B over an incomplete
      // set.
      const seen = new Set();
      const restored = [];
      for (const entry of [...captured, ...this.#pending]) {
        const key = String(entry?.name);
        if (seen.has(key)) continue;
        seen.add(key);
        restored.push(entry);
      }
      this.#pending = restored.slice(-PENDING_LIMIT);
      // Whole-group rollback: the group record is withdrawn first (the group is
      // about to not exist), then every lane this call built is handed back.
      // What the rollback did is attached to the refusal, because a caller
      // cannot otherwise tell a clean teardown from a lane left running.
      if (committed) {
        const discard = step(this.#port, "discardGroup");
        if (discard !== null) {
          try {
            const withdrawn = await discard({ groupId, owner, lanes });
            if (withdrawn !== null && typeof withdrawn === "object") {
              Object.defineProperty(error, "discard", { value: withdrawn, enumerable: true });
            }
          } catch (discardError) {
            Object.defineProperty(error, "discardFailure", { value: discardError, enumerable: true });
          }
        }
      }
      if (built > 0) {
        try {
          const aborted = await this.#port.abortLanes({
            groupId,
            laneIds: lanes.slice(0, built).map((lane) => lane.laneId),
          });
          if (aborted !== null && typeof aborted === "object") {
            Object.defineProperty(error, "rollback", { value: aborted, enumerable: true });
          }
        } catch (abortError) {
          // The original failure is the one that explains the refusal; the
          // rollback failure is reported beside it, never instead of it.
          Object.defineProperty(error, "abortFailure", { value: abortError, enumerable: true });
        }
      }
      throw error;
    } finally {
      this.#building.delete(owner);
      this.#buildingGroups.delete(owner);
    }
    this.#groups.set(owner, group);
    return { ok: true, groupId, lanes: group.lanes };
  }

  // A lane handed its work in. Spec §4.2 asks each lane to deliver, not the
  // host to acknowledge the merge; the answer says whether the group is ready.
  //
  // The delivery is booked through `settle`, so a delivery that completes
  // `ConvergenceReady` announces exactly like a settlement would: convergence is
  // a conjunction of facts, not the order they arrive in.
  deliver(owner, laneIndex, result = null) {
    const receipt = this.settle(owner, laneIndex, result);
    return receipt === null ? this.#requireGroup(owner).deliver(laneIndex) : receipt.ready === true;
  }

  // One lane's turn settled (spec §4.2 steps 8 and 10).
  //
  // The settlement is the lane's DELIVERY. Whichever delivery makes
  // `ConvergenceReady` hold — the takeover lane's own, or a sibling's after the
  // takeover lane already delivered — is also the announcement that ends the
  // group, and the announced result is ALWAYS the takeover lane's stored report,
  // never the last arrival's. The answer is the receipt of what the settlement
  // did, or `null` for a name no open group owns (a `fork` worker's turn).
  //
  // A second settlement of the same lane is not a second delivery: the fact is
  // the same, and `LaneGroup.deliver` is idempotent for it. A settlement of a
  // group that is already terminal is answered, not thrown: a host reports what
  // a lane did, and a report that arrives late is still not an error of the
  // host's.
  settle(owner, laneIndex, result = null) {
    // A lane may settle while its group is still being built (a one-step
    // host). The building map is consulted first so that settlement is the
    // lane's delivery rather than "no group".
    const group = this.#buildingGroups.get(owner) ?? this.#groups.get(owner);
    if (group === undefined) return null;
    if (group.phase === GROUP_STATES.CONVERGED || group.phase === GROUP_STATES.FAILED) {
      return { groupId: group.groupId, owner, lane: laneIndex, terminal: group.phase };
    }
    const ready = group.deliver(laneIndex, result);
    // §4.2 step 10: only the takeover lane announces, and only once
    // `ConvergenceReady` holds. `announcement()` answers that lane and its own
    // stored report, so this call announces whatever lane completed the
    // conjunction and always with lane n−1's report.
    const announcement = group.announcement();
    if (announcement === null) {
      return { groupId: group.groupId, owner, lane: laneIndex, delivered: true, ready };
    }
    return {
      groupId: group.groupId,
      owner,
      lane: laneIndex,
      delivered: true,
      ready,
      // The takeover lane announces, exactly once: a repeated announcement of
      // the same result is idempotent (`LaneGroup.converge`).
      converged: group.converge({ lane: announcement.lane, result: announcement.result }),
    };
  }

  // A completion captured before fission, delivered to one lane.
  broadcast(owner, completion) {
    return this.#requireGroup(owner).broadcast(completion);
  }

  claim(owner, laneIndex, completionKey) {
    return this.#requireGroup(owner).claimBroadcast(laneIndex, completionKey);
  }

  converge(owner, laneIndex, result) {
    return this.#requireGroup(owner).converge({ lane: laneIndex, result });
  }

  fail(owner, reason) {
    return this.#requireGroup(owner).fail(reason);
  }

  // The group's state as §4.2 names it: `admitted` -> `started` -> `replaced`
  // -> `ready` (ConvergenceReady) -> `converged` | `failed`.
  stateOf(owner) {
    return this.#requireGroup(owner).phase;
  }

  #requireGroup(owner) {
    const group = this.#groups.get(owner);
    if (group === undefined) {
      throw new CoreError(CODES.INVALID_ARGUMENT, `program ${owner} has no fission group`, { owner });
    }
    return group;
  }
}

export { GROUP_STATES };
