// Host ports for the compose surfaces (`compose/ports.mjs` `PORT_METHODS`).
//
// A port is a plain object of async functions. Everything here goes through a
// real host capability; when the host does not give us one, the port reports
// `ok: false` with a named reason instead of inventing an answer. Compose then
// fails the review or refuses the fork, and the ledger stays where it was.

import { nodeCommandRunner } from "../core/command.mjs";
import { Fission } from "../compose/fission/fission.mjs";
import { createOperatorSeam } from "./operator.mjs";
import { planHandoff } from "./handoff.mjs";
import { appendJsonLine } from "./files.mjs";

/** `command.run(command, {cwd, env, onData})` — one shell command (spec §3.3). */
export const commandPort = ({ cwd, timeoutMs = 600_000 }) => ({
  async run(command, options = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(new Error(`command timed out after ${timeoutMs}ms`)), timeoutMs);
    try {
      const receipt = await nodeCommandRunner(command, {
        cwd: options.cwd ?? cwd,
        ...(options.env === undefined ? {} : { env: { ...process.env, ...options.env } }),
        signal: controller.signal,
        // The runner reports `{ stream, chunk }`; the compose `call` member
        // hands that straight to the command view.
        onData: (event) => options.onData?.(event),
      });
      return {
        stdout: receipt?.stdout ?? "",
        stderr: receipt?.stderr ?? "",
        exitCode: typeof receipt?.exitCode === "number" ? receipt.exitCode : -1,
      };
    } finally {
      clearTimeout(timer);
    }
  },
});

// `relay` (根 §2.3, §6.1): the three side effects the host must perform.
//
//   handOver(decision)      retirement, the context barrier and the successor
//                           start as one record (`TenureState.handOver`)
//   startManager(draft)     a Manager session from the draft bytes
//   askOperator(question)   the first incumbent's question, through the seam
//                           (`operator.mjs`). The question is parked as an
//                           `ask` and the turn is interrupted, so the operator
//                           answers it in ordinary chat (§2.4 第 1 步)
//
// The barrier is what the request projection cuts at (see `hooks.mjs`): the
// host's stored history is never edited, the successor's view is.
//
// A Plan's hand-off does not end the plan and does not replace anything
// (§6.1): `startManager` only opens a second, independent session, and the
// receipt is the host's answer. A host that started a session but could not
// give it the draft stops that session again (`planHandoff`), so a refused
// hand-off leaves no half-open Manager behind.
export const relayPort = ({ tenure, sessions, operator = null }) => ({
  async handOver(decision) {
    if (decision?.retire !== true || decision?.openSuccessor !== true || decision?.barrier !== true) {
      return { ok: false, reason: "INCOMPLETE_RELAY" };
    }
    return tenure.handOver({ lastReview: tenure.ledger.reviewCount, lastTurn: tenure.turns });
  },
  async startManager(draft) {
    if (typeof draft !== "string" || draft.length === 0) {
      return { ok: false, reason: "DRAFT_MISSING" };
    }
    if (sessions === null || typeof sessions?.startManager !== "function") {
      return { ok: false, reason: "HOST_HAS_NO_SESSION_CREATE" };
    }
    return planHandoff({
      draft,
      startManager: (text) => sessions.startManager(text),
      stopManager: typeof sessions?.stopManager === "function" ? (id) => sessions.stopManager(id) : null,
    });
  },
  async askOperator(question) {
    // The question is the same `ask` the surface performs: park the work, hand
    // control back to the operator, and let the reply resume it. The seam
    // (`operator.mjs`) always answers with the asked receipt, so a first empty
    // review on an empty ledger asks the operator on every session — it never
    // refuses for a missing channel (根 §2.4 第 1 步).
    const seam = operator !== null && typeof operator?.ask === "function" ? operator : createOperatorSeam();
    return seam.ask(question);
  },
});

// The three sentences that open every lane (spec §4.2, step 3). They state the
// one fact a lane could otherwise misread: it is a parallel present of the same
// participant, not a delegate with siblings to command.
//
// `laneCount` is the group's lane count, which compose fixes once for every
// lane; a lane's index is not a count.
export const laneFraming = (laneCount) =>
  [
    `You are one of ${laneCount} parallel presents of the same worker identity, not a new delegate.`,
    "Your input below is your whole current responsibility.",
    "Continue that same responsibility; do not treat the sibling lanes as agents delegated to you.",
  ].join("\n");

// The host steps one fission group drives (spec §4.2, `compose/fission`).
//
// `buildLane(lane)` receives compose's lane (`{index, laneId, prompt,
// laneCount, groupId, owner, view}`) and builds the lane's runtime WITHOUT its
// first turn. The framing is prepended here, so the lane's start context is
// the fixed three sentences (with the group's lane count), the owner's last
// delivered view when there is one, and the lane's own prompt.
//
// `startLane(lane)` then starts that runtime's first turn. The split is what
// makes step 4 possible: every lane is BUILT inert, compose commits the group
// record, and only then does any lane start, so a crash can never leave a LIVE
// lane whose group nobody recorded. A host with no way to build without
// starting (`buildLane` absent) keeps the older one-step shape: `createLane`
// builds and starts, and `startLane` confirms it.
//
// `discardGroup` withdraws the group's durable record when the group rolls
// back, so a journal never keeps a group whose lanes were torn down.
//
// `replacePresent` and `abortLanes` are supplied by the worker table, which is
// the only place that holds a runtime handle.
export const fissionSteps = ({
  ownerView,
  createLane = null,
  buildLane = null,
  startLane = null,
  discardGroup = null,
  replacePresent,
  abortLanes,
  groupJournal = null,
}) => {
  if (buildLane === null && createLane === null) {
    throw new TypeError("fissionSteps needs buildLane (two-step) or createLane (one-step)");
  }
  const lanes = new Map();
  // The journal is a path or a function that answers one. A session owns the
  // path and passes a function (the root may not be known yet); a test or a
  // caller with a fixed file passes the path itself.
  const journalPath = () => {
    if (typeof groupJournal === "function") return groupJournal();
    if (typeof groupJournal === "string" && groupJournal.length > 0) return groupJournal;
    return null;
  };
  const ready = (lane) => {
    // Compose always sends the group's count; `undefined` would print "one of
    // undefined", so a missing fact is reported instead of papered over.
    if (!Number.isInteger(lane.laneCount) || lane.laneCount < 2) {
      throw Object.assign(new Error(`lane ${lane.laneId} carries no lane count`), {
        code: "LANE_COUNT_MISSING",
      });
    }
    const context = [laneFraming(lane.laneCount)];
    if (typeof lane.view === "string" && lane.view.length > 0) context.push(lane.view);
    context.push(lane.prompt);
    return { ...lane, prompt: context.join("\n\n") };
  };
  const port = {
    ownerView,
    // The group record: what was built, in lane order (spec §4.2 step 4, "原子持久
    // 提交：将整组 lane 的创建事实作为一笔持久记录提交").
    //
    // ONE append, not one per lane: a lane set that is half-written is not a group,
    // and `appendJsonLine` writes a single line, so a reader either sees the whole
    // record or does not see it. The file is `.Herdchestra/<session>.fission.jsonl`
    // (`groupJournal` names it; the session owns the path).
    //
    // A group whose record cannot be written is NOT reported as persisted: the
    // caller gets `durable: false` and the reason, so a receipt never claims a
    // durable commit the filesystem refused. A commit that is not durable is a
    // REFUSAL here, not a success with a caveat: step 4 is what makes a crash
    // survivable, and a group without it must not start.
    async persistGroup({ groupId, owner, lanes: groupLanes }) {
      const laneIds = groupLanes.map((lane) => lane.laneId);
      if (groupJournal === null) {
        return { ok: false, groupId, owner, lanes: laneIds, durable: false, reason: "NO_GROUP_JOURNAL" };
      }
      let target;
      try {
        target = journalPath();
      } catch (error) {
        return { ok: false, groupId, owner, lanes: laneIds, durable: false, reason: "JOURNAL_PATH_UNRESOLVED", message: String(error?.message ?? error) };
      }
      if (typeof target !== "string" || target.length === 0) {
        return { ok: false, groupId, owner, lanes: laneIds, durable: false, reason: "JOURNAL_PATH_UNRESOLVED" };
      }
      try {
        appendJsonLine(target, {
          v: 1,
          at: new Date().toISOString(),
          kind: "fissionGroup",
          groupId,
          owner,
          lanes: laneIds,
        });
      } catch (error) {
        return {
          ok: false,
          groupId,
          owner,
          lanes: laneIds,
          durable: false,
          reason: error?.code ?? "JOURNAL_WRITE_FAILED",
          message: String(error?.message ?? error),
        };
      }
      return { ok: true, groupId, owner, lanes: laneIds, durable: true, file: target };
    },
    // Withdraw the record a rolled-back group wrote. The journal is append-only,
    // so the withdrawal is its own line (`kind: "fissionGroupDiscarded"`) that a
    // reader applies in order: the group's creation is cancelled, and the
    // record of WHY it was cancelled stays visible.
    async discardGroup({ groupId, owner, lanes: groupLanes }) {
      if (groupJournal === null) {
        return { ok: false, groupId, reason: "NO_GROUP_JOURNAL" };
      }
      let target;
      try {
        target = journalPath();
      } catch (error) {
        return { ok: false, groupId, reason: "JOURNAL_PATH_UNRESOLVED", message: String(error?.message ?? error) };
      }
      if (typeof target !== "string" || target.length === 0) {
        return { ok: false, groupId, reason: "JOURNAL_PATH_UNRESOLVED" };
      }
      try {
        appendJsonLine(target, {
          v: 1,
          at: new Date().toISOString(),
          kind: "fissionGroupDiscarded",
          groupId,
          owner,
          lanes: groupLanes.map((lane) => lane.laneId),
        });
      } catch (error) {
        return { ok: false, groupId, reason: error?.code ?? "JOURNAL_WRITE_FAILED", message: String(error?.message ?? error) };
      }
      return { ok: true, groupId, durable: true, file: target };
    },
  };
  if (buildLane !== null) {
    // Two-step: build the runtime inert, start its turn after the commit.
    port.buildLane = async (lane) => {
      const built = await buildLane(ready(lane));
      if (built !== null && typeof built === "object" && built.ok === false) {
        throw Object.assign(new Error(built.message ?? `lane ${lane.laneId} was not built`), {
          code: built.reason ?? built.rejection ?? "LANE_BUILD_FAILED",
          step: "buildLane",
        });
      }
      lanes.set(lane.laneId, built);
      return { ok: true, laneId: lane.laneId };
    };
    port.startLane = async (lane) => {
      const built = lanes.get(lane.laneId) ?? null;
      const started = await startLane({ ...ready(lane), built });
      if (started !== null && typeof started === "object" && started.ok === false) {
        throw Object.assign(new Error(started.message ?? `lane ${lane.laneId} did not start`), {
          code: started.reason ?? started.rejection ?? "LANE_START_FAILED",
          step: "startLane",
        });
      }
      return { ok: true, laneId: lane.laneId };
    };
  } else {
    // One-step: the runtime's first run IS its start.
    port.createLane = async (lane) => createLane(ready(lane));
    port.startLane = async (lane) => {
      const confirmed = typeof startLane === "function" ? await startLane(lane) : { ok: true, laneId: lane.laneId };
      return confirmed;
    };
  }
  port.replacePresent = replacePresent;
  port.abortLanes = abortLanes;
  return port;
};

// The `fission` port of a worker (spec §4.2, root §7.2).
//
// compose's surface calls `port.fission(owner, prompts)`; that is the group
// operation, and the steps above are what it drives. `origins(name)`
// answers whether the name is a fork-started worker of this session, and
// `capacity(count)` answers whether the host can take the whole group.
//
// A group is never reused after it is built: `Fission` refuses a second group of
// the same owner with `AlreadyActive`, exactly as §4.2 requires.
export const fissionPort = ({ ownerName, origins, capacity = () => true, steps }) => {
  // compose's `Fission` drives `ownerView`, then one of `buildLane`/`createLane`,
  // then `persistGroup`, `startLane`, `replacePresent` and `abortLanes`
  // (`compose/fission/port.mjs`). The lane count is compose's fact and travels
  // ON the lane it hands to the builder (`lane.laneCount`); this host reads it
  // and never counts.
  const port = { ...steps };
  const groups = new Fission({ port, origins, capacity });
  return {
    ownerName: typeof ownerName === "function" ? ownerName : null,
    fission: (owner, prompts) => groups.fission(owner, prompts),
    // The steps stay reachable for a caller that drives them itself (a test, or
    // a later host step that needs the same lane table).
    ...port,
  };
};
