// Fission through the worker table (根 AGENTS.md §4.2, §7.2).
//
// compose owns every rule of a fission group (`compose/fission`): admission,
// the whole-group rollback, `ConvergenceReady`, merge order, the one terminal
// lane. This module supplies only the facts compose asks for, each one read from
// the worker table this adapter already keeps:
//
//   origins(owner)   a worker `fork` started, not a lane, not the session
//   capacity(count)  the host can start `count` more delegated runtimes
//   ownerView(owner) the owner's last delivered turn text, or null
//   buildLane        one delegated runtime of the owner's role, built INERT
//   startLane        that runtime's first turn, after the record is durable
//   replacePresent   the owner's old present stops producing turns
//   abortLanes       every lane of a failed group is stopped
//
// The owner is the SESSION, not the program: a worker program is named by its
// tool-call id, and a group id built from that names nothing a caller can trace
// back. A session's own `js-worker` call is therefore not an origin either — only
// a worker that `fork` started is.
//
// `persistGroup` writes the group's creation as ONE record (spec §4.2 step 4) to
// `<root>/.Herdchestra/<session>.fission.jsonl`. `groupJournal` names that file;
// the session owns the root, so the adapter never derives a second path.

import { ROLES } from "../compose/program/surface.mjs";
import { Fission } from "../compose/fission/fission.mjs";
import { fissionSteps } from "./ports.mjs";

// How many delegated runtimes this session will keep alive at once. It is a
// ceiling this adapter chooses, not a host number: a lane is a real runtime
// spending the operator's tokens, and §4.2 step 1 asks for a capacity check
// that can REFUSE. A group that does not fit is refused as `CapacityExceeded`
// before a single lane exists.
export const LANE_CAPACITY = 8;

export class SessionFission {
  #workers;
  #sessionId;
  #groupJournal;
  #group = null;
  #owner = null;
  #capacity;
  // The delivery a lane's turn end becomes (§4.2 step 8). One group per session,
  // so one listener: registered when the port is first built and removed when
  // the session ends.
  #unlisten = null;
  // Where each lane belongs, by lane id: `{ owner, index }`. A lane's turn end
  // names its own id, and the group that owns it is the group of the owner the
  // lane was built for. One session-wide `#owner` slot misrouted every
  // settlement as soon as two workers fissioned, or a second worker's refused
  // fission rebound the slot: the first group's deliveries then hit an owner
  // with no group, never reached `ConvergenceReady`, and never announced.
  #lanes = new Map();

  constructor({ workers, sessionId, groupJournal = null, capacity = LANE_CAPACITY }) {
    this.#workers = workers;
    this.#sessionId = sessionId;
    this.#groupJournal = groupJournal;
    this.#capacity = capacity;
  }

  // The port compose's surface calls: `fission(owner, prompts)` is the group
  // operation, and the steps are the same object's other members.
  port() {
    this.#watchLanes();
    const steps = fissionSteps({
      ownerView: async (owner) => this.#workers.viewOf(owner),
      // §4.2 steps 3 to 5: the lane's runtime is built INERT here, the group
      // record is committed by compose, and only then does `startLane` send the
      // lane's first turn. A host that cannot build without starting reports
      // it, and compose starts it here.
      buildLane: async (lane) => this.#buildLane(lane),
      startLane: async (lane) => this.#startLane(lane),
      replacePresent: async ({ owner }) => this.#workers.replacePresent(owner),
      // The table's receipt is already `{ ok, released, orphaned }`
      // (`compose/fission/port.mjs`); wrapping it again would hide an orphaned
      // lane inside a `released` array and report a failed teardown as clean.
      abortLanes: async ({ laneIds }) => {
        const receipt = this.#workers.abortLanes(laneIds);
        // A rolled-back lane has no group left to deliver to.
        for (const entry of Array.isArray(laneIds) ? laneIds : []) {
          const laneId = typeof entry === "string" ? entry : entry?.laneId;
          if (typeof laneId === "string") this.#lanes.delete(laneId);
        }
        return receipt;
      },
      // Where a group's creation is committed. A session without a root has no
      // journal, and `persistGroup` then REFUSES (`NO_GROUP_JOURNAL`): step 4 is
      // what makes a crash survivable, so a group that cannot be recorded does
      // not start.
      groupJournal: this.#groupJournal,
    });
    return {
      ownerName: () => this.#sessionId,
      ...steps,
      fission: (owner, prompts) => this.#fission(owner, prompts),
    };
  }

  // One group per session, built lazily. compose refuses a second group of one
  // owner with `AlreadyActive`, so the instance lives as long as the session.
  #groups() {
    if (this.#group === null) {
      this.#group = new Fission({
        port: this.port(),
        origins: (owner) => this.#workers.isForked(owner),
        // The host's real room (spec §4.2 step 1): the runtimes this session
        // already holds plus the whole group must fit under the ceiling. A host
        // that cannot measure its room answers `false`, which refuses the group
        // as `CapacityExceeded` instead of starting lanes nobody counted.
        capacity: (laneCount) => this.#workers.capacityFor(laneCount, { limit: this.#capacity }),
      });
    }
    return this.#group;
  }

  async #fission(owner, prompts) {
    const receipt = await this.#groups().fission(owner, prompts);
    // The reader's owner is the one whose group was ADMITTED: a refused second
    // fission must not point `group` at a group that does not exist.
    if (receipt?.ok === true) this.#owner = owner;
    return receipt;
  }

  // Every lane settlement becomes a delivery to its group (§4.2 steps 8 and 10):
  // the lane's turn end is condition A, and the takeover lane's delivery is the
  // announcement that ends the group. A `fork` worker's turn is not a lane and
  // never reaches here.
  #watchLanes() {
    if (this.#unlisten !== null) return;
    this.#unlisten = this.#workers.onLaneSettled((settled) => this.#laneSettled(settled));
  }

  #laneSettled(settled) {
    if (this.#group === null) return null;
    const lane = this.#lanes.get(settled?.name);
    if (lane === undefined || !Number.isInteger(lane.index)) return null;
    const text = typeof settled?.outcome?.text === "string" ? settled.outcome.text : null;
    return this.#group.settle(lane.owner, lane.index, text);
  }

  // A lane's turn settled: its delivery, and the takeover lane's announcement
  // (§4.2 steps 8 and 10). `settled` is the table's lane record
  // (`{ name, lane, outcome }`); the name is the lane id, which is the index the
  // group addresses the lane by.
  laneSettled(settled) {
    return this.#laneSettled(settled);
  }

  // The session ends: a listener left registered would outlive the session it
  // reports to.
  stop() {
    if (this.#unlisten !== null) this.#unlisten();
    this.#unlisten = null;
    return { ok: true };
  }

  // The group of the owner that called `fission`, or null. A receipt or a test
  // reads it; `null` before the first group and after a rollback.
  get group() {
    if (this.#group === null || this.#owner === null) return null;
    return this.#group.groupOf(this.#owner);
  }

  async #buildLane(lane) {
    const owner = this.#workers.get(lane.owner);
    // The owner and index travel with the lane id from the moment the lane
    // exists, so a turn that ends while the group is still building is booked to
    // the right group.
    this.#lanes.set(lane.laneId, { owner: lane.owner, index: lane.index });
    const built = await this.#workers.startLane({
      name: lane.laneId,
      brief: lane.prompt,
      role: owner?.role === ROLES.DEVOPS ? ROLES.DEVOPS : ROLES.CODER,
      // Built, not started: the group record is committed between the two.
      start: false,
    });
    if (built?.started === false) {
      // A refused lane fails the group exactly like a thrown error: compose then
      // rolls back every lane it already built.
      throw Object.assign(new Error(built.message ?? "the host could not build a lane"), {
        code: built.rejection ?? "LANE_BUILD_FAILED",
        step: "buildLane",
      });
    }
    return { laneId: lane.laneId, pending: built.pending === true };
  }

  async #startLane(lane) {
    const worker = this.#workers.get(lane.laneId);
    if (worker === null) {
      throw Object.assign(new Error(`lane ${lane.laneId} was not built`), { code: "LANE_MISSING", step: "startLane" });
    }
    // A lane whose runtime already started its turn at build time (a host that
    // cannot build inert) has nothing left to send, and compose's start is the
    // confirmation.
    if (worker.admitted > 0 && worker.settled >= worker.admitted) return { ok: true, laneId: lane.laneId };
    const begun = this.#workers.beginLane(lane.laneId, lane.prompt);
    if (begun.ok !== true) {
      throw Object.assign(new Error(`lane ${lane.laneId} did not start: ${begun.rejection}`), {
        code: begun.rejection ?? "LANE_START_FAILED",
        step: "startLane",
      });
    }
    return { ok: true, laneId: lane.laneId };
  }
}
