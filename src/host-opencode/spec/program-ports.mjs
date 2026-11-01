// The ports compose asks a program surface for (§7.1, compose/ports.mjs).
//
// Each port is a plain object of async functions. What the host cannot do is
// not left out silently: the port exists and answers with a named refusal, so
// the model reads a reason instead of a TypeError.
//
// Port method names are compose's (`PORT_METHODS`), not the host's:
//
//   fork      fork(name, brief)                 Manager surface `fork`
//   join      join()                            Manager surface `join`
//   relay     handOver(decision) | startManager(draft) | askOperator(question)
//   control   unpark({ controllerId, name, value })
//   fission   ownerView() | buildLane(lane) | persistGroup(group)
//             | discardGroup({groupId}) | startLane(lane) | replacePresent(group)
//             | abortLanes({groupId, laneIds})
//   devops    investigate(intent)               Plan surface `devops`
//   draft     read()                            Plan's saved draft bytes
//   command   run(command, { onData }) -> { stdout, stderr, exitCode }
//
// `review` is not a port: compose's surface builds its own `ReviewMember`
// against the session's ledger and calls the `relay` object for side effects.
//
// The lane port reads and writes the adapter's own fission tables, which hang
// off the activation (`compose/fission/fission.mjs` for the rules, this file for
// the host calls). A lane's prompt is stored when it is created and sent when
// the group starts; `persistGroup` records the group in the owner's state file
// so a cold start knows which sessions a group owns.

import path from "node:path";
import { mkdirSync } from "node:fs";
import { readFile } from "node:fs/promises";

import { refusal, REFUSALS } from "./refusals.mjs";
import { stateOf, FISSION_CAPACITY, laneRoleOf } from "./activation.mjs";
import { requireModelOf } from "./model-inherit.mjs";

// Plan's first empty review asks the operator (根 §2.4 第 1 步). It is the SAME
// question `this.ask` asks, and it takes the same one step: the review parks
// through the surface's `parkChannel`, which the executor states for the call
// (`program-tool.mjs`). The park is what records the question, shows it with
// `Session.synthetic` and interrupts the turn — `program-tool.mjs` does all
// three for every `ask` site, whichever member reached it.
//
// So this port performs NO side effect of its own. It answers the question that
// was asked, and that answer is the `askUser` receipt's `answer` field. Parking
// here as well would be a second mechanism for one fact: the body would be
// suspended on the port's promise instead of the park the wrapper can see, and
// the call would answer `completed` instead of the park receipt.
const askOperator = async (activation, question) => {
  const sessionID = activation.askSession ?? null;
  const program = activation.askProgram ?? null;
  return { ok: true, asked: true, question, program, sessionID, interrupt: true, parked: true };
};

export const makeRelayPorts = (activation) =>
  Object.freeze({
    // Retirement, the barrier and the successor start are one step (根 §2.4 第 3 步).
    // `relaySession` and `relayLedger` are set by the plugin for the duration of
    // one Manager program call; without them the step cannot be performed.
    handOver: async (decision) => {
      if (decision?.retire !== true || decision?.openSuccessor !== true || decision?.barrier !== true) {
        return { ok: false, reason: "INCOMPLETE_RELAY" };
      }
      // The predecessor and its ledger travel on the decision: compose's
      // `ReviewMember` calls `handOver` with the three flags only, so the
      // surface passes what it knows through `activation.relayContext`, which
      // the plugin sets for the duration of one call.
      const context = activation.relayContext ?? null;
      if (context === null) return { ok: false, reason: "NO_SESSION_DOMAIN" };
      return activation.relayHost.handOver({
        predecessor: context.sessionID,
        milestone: context.ledger.milestone,
        tenure: context.ledger.tenure,
      });
    },
    startManager: async (draft) => {
      // The Plan's own operator input travels with the hand-off (根 §2.4 第 4 步:
      // the successor sees "用户原始输入"). The Plan holds it as a durable fact;
      // reading it here keeps this port from guessing from a transcript.
      const context = activation.relayContext ?? null;
      const operatorInput =
        context === null ? null : (stateOf(activation, context.sessionID).originalInput()?.text ?? null);
      return activation.relayHost.startManager(draft, {
        operatorInput,
        // The Plan's own session: its model is what the Manager must run.
        planSession: context === null ? null : context.sessionID,
        // §2.4 第 6 步: the physical call id is the ONLY thing a repeat is
        // matched on. `relayContext.id` is the host's call id for the program
        // running this review, so the same call replayed reports the Manager it
        // already started, while a new empty review — identical draft or not —
        // starts its own (§2.2).
        handoffId: context === null ? null : (context.id ?? null),
      });
    },
    askOperator: async (question) => askOperator(activation, question),
  });

export const makeControlPort = (activation, sessionID) =>
  Object.freeze({
    // compose passes the calling program's name as `controllerId`. Core checks
    // that this request observed the target and that the controller holds the
    // target's capability set; the resumer then settles the parked promise, so
    // the original body continues at the park site.
    //
    // `capabilities` is what the controller actually holds: the set it was
    // started with, read from its own program. Both programs of one session
    // carry the same set (the face's role), so `unpark` passes exactly when the
    // controller and the target belong to one session and one role — including
    // the `Plan` that resumes its own `ask` park. `targetCapabilities` is left
    // null: it is an override, and core reads the target's own set.
    unpark: async (request) => {
      const state = stateOf(activation, sessionID);
      const controller = state.calls.program(request.controllerId);
      const outcome = state.control.unpark({
        controllerId: request.controllerId,
        name: request.name,
        value: request.value ?? null,
        capabilities: controller === null ? [] : [...controller.capabilities],
        deadline: request.deadline ?? null,
      });
      return activation.resume({
        name: request.name,
        value: outcome.value,
        site: outcome.site,
        sessionID,
      });
    },
  });

// `fork(name, brief, role)`: compose resolved the role from the brief's own
// declaration (§2.1, §4.1) and passes it here, so this port neither defaults nor
// re-reads the brief. `join()` takes nothing (§4.1).
export const makeForkJoinPorts = (activation, sessionID) =>
  Object.freeze({
    fork: async (name, brief, role) => activation.forkWorker({ sessionID, name, brief, role }),
    join: async () => activation.joinWorker({ sessionID }),
  });

// `devops` (spec §6): one investigation for Plan. It starts a devops worker and
// waits for that one answer, then returns the report whole; nothing here
// summarises it. At most one devops is in flight per Plan session.
export const makeDevopsPort = (activation) =>
  Object.freeze({
    investigate: async (intent) => {
      const sessionID = activation.devopsSession ?? null;
      if (sessionID === null) return refusal(REFUSALS.ROLE, { member: "devops" });
      const inFlight = activation.devopsRuns.get(sessionID);
      if (inFlight !== undefined) return refusal(REFUSALS.DEVOPS_BUSY, { name: inFlight });
      const name = `plan-devops-${Date.now().toString(36)}`;
      activation.devopsRuns.set(sessionID, name);
      try {
        // The identity is stated in the brief itself, exactly as a Manager's
        // brief must (§2.1, §4.1); `role` is passed beside it for the same
        // resolution every other fork uses.
        const run = await activation.forkWorker({
          sessionID,
          name,
          brief: `role: devops — ${intent}`,
          role: "devops",
          wait: true,
        });
        if (run === null) return refusal(REFUSALS.GAP, { gap: "FORK_SESSIONS" });
        if (run.ok === false) return run;
        return { ok: true, name: run.name ?? name, report: run.report ?? null };
      } finally {
        activation.devopsRuns.delete(sessionID);
      }
    },
  });

// Plan's saved draft (§2.2). One persistent product, one path, derived from the
// session — the same rule `host-cline` (`drafts.mjs`) and `host-omp`
// (`host-facts.mjs`) use, so one work item keeps one draft across every host.
// A missing draft is an empty string, and the review ledger refuses to start a
// Manager session from it.
export const PLAN_DRAFT_DIR = path.join(".Herdchestra", "drafts");

export const planDraftPath = (root, name) => path.join(root, PLAN_DRAFT_DIR, `${name}.md`);

// The saved draft of one session, or "" when the file does not exist yet. Any
// other read failure is a real error and is reported, not turned into an empty
// draft.
export const readDraftFile = async (file) => {
  try {
    return await readFile(file, "utf8");
  } catch (error) {
    // No draft is a legitimate answer, and so is a draft that cannot exist:
    // `ENOENT` when the file is missing, `ENOTDIR` when a path component is a
    // file. Both mean "there is no draft here", which is exactly what the
    // review ledger needs in order to refuse a hand-off with `DRAFT_MISSING`.
    // Any other failure is a real read error and is reported.
    if (error?.code === "ENOENT" || error?.code === "ENOTDIR") return "";
    throw error;
  }
};

// Create the directory a draft lives in, once, before anything can write it.
//
// `atomicWriteFile` creates its temp file INSIDE the target's directory
// (`core/fs.mjs`), so a draft written before `.Herdchestra/drafts/` exists fails
// with a bare `ENOENT` from `write` — an error about a temp file, in a
// directory the Plan never named and cannot create (Plan has no `mkdir`). The
// directory is therefore prepared where the port is built, and a directory that
// cannot be prepared is reported as itself, not discovered later as a mystery
// write failure.
//
// Same contract as `host-cline` (`drafts.mjs` `ensureDraftDir`), so both hosts
// answer the same question with the same words.
export const ensureDraftDir = ({ file }) => {
  const directory = path.dirname(file);
  try {
    mkdirSync(directory, { recursive: true });
    return { ok: true, directory };
  } catch (error) {
    return { ok: false, reason: "DRAFT_DIR_UNWRITABLE", directory, detail: String(error?.message ?? error) };
  }
};

// `root` and `name` are read per call, not captured: a session that switches
// record still names the file its task fixed.
//
// `prepared` is the result of `ensureDraftDir` for this session's draft, done
// once when the port is built. The port still answers `path()` and `read()`
// when preparation failed — reading a draft that is not there is legitimately
// `""` — and exposes `dirFailure` so the host can state the failure instead of
// letting the first write surface it as `ENOENT`.
export const makeDraftPort = ({ root, name }) => {
  const file = planDraftPath(root, name);
  const prepared = ensureDraftDir({ file });
  return Object.freeze({
    path: () => file,
    read: () => readDraftFile(file),
    dirFailure: () => (prepared.ok === true ? null : prepared),
  });
};

// The lane's start text (§4.2 step 3): three fixed sentences, the owner's last
// delivered view, and the lane count. The lane keeps the owner's identity,
// permissions, responsibility and work tree; only its input differs.
//
// `laneCount` is the group's count, fixed once by compose and carried on the
// lane (`lanesOf`). The lane's `index` is its position, not a count.
export const lanePrompt = ({ lane, view = null }) => {
  const laneCount = lane.laneCount;
  const head = [
    `You are one of ${laneCount} parallel presents of the same worker identity; this lane is number ${lane.index}.`,
    `Your input below is your current responsibility. Continue the same responsibility as the worker you are part of; do not treat the other lanes as agents delegated to you.`,
    `All lanes share one work tree and one identity. Work only inside the part of the task your input names.`,
  ];
  const body = [`# input`, lane.prompt];
  if (typeof view === "string" && view.length > 0) body.push("# the worker's last delivered view", view);
  return [...head, ...body].join("\n\n");
};

// The fission port of one owner (§4.2 steps 2-6). It talks to the host only
// through the session domain and keeps the lane sessions by group id, so
// `abortLanes` can silence exactly what this group created.
export const makeFissionPorts = (activation) => {
  const portFor = (owner) => {
    const port = {
      // The owner's last delivered view: the text of its last completed answer,
      // or null when it has none.
      ownerView: async () => {
        const view = stateOf(activation, owner).lastView ?? null;
        return typeof view === "string" && view.length > 0 ? view : null;
      },
      // §4.2 step 3: build the lane INERT. The session is created and
      // registered, but its first input is sent by `startLane`, after the group
      // record is durable (step 4). A host whose create already prompts (a
      // create hook) answers that in the start step, which then confirms it.
      buildLane: async (lane) => {
        if (activation.sessions === null) return refusal(REFUSALS.GAP, { gap: "FORK_SESSIONS" });
        // A lane is the owner's parallel present (§4.2): it runs what the owner
        // runs. The host does not inherit a model for a created child
        // (`model-inherit.mjs`), and the agent default is the free model that
        // answers 429.
        const inherited = await requireModelOf(activation.sessions, owner);
        if (inherited.ok !== true) return inherited.refusal;
        // A lane is the owner's parallel present, so it runs the owner's
        // identity (§4.2): a devops that fissions starts devops lanes.
        const laneRole = laneRoleOf(activation, owner);
        const created = await activation.sessions.create({
          title: lane.laneId,
          agent: laneRole,
          model: inherited.model,
          metadata: { herdchestra: "lane", groupId: lane.groupId, lane: lane.index },
        });
        const sessionID = created?.data?.id ?? created?.id ?? null;
        if (typeof sessionID !== "string" || sessionID.length === 0) {
          throw new Error("the host returned no lane session id");
        }
        // Registered before the lane's first prompt: a lane's input is its
        // brief, not the operator's original words (`identity.mjs`). A lane has
        // the owner as its parent, so it is never treated as top-level.
        activation.registerIdentity(sessionID, {
          parentID: typeof owner === "string" && owner.length > 0 ? owner : null,
          role: laneRole,
          origin: "lane",
        });
        activation.lanes.set(lane.laneId, {
          laneId: lane.laneId,
          sessionID,
          groupId: lane.groupId,
          owner,
          // The identity the lane runs as, recorded with the session so a later
          // event that names only the session can still tell what it is.
          role: laneRole,
          index: lane.index,
          // Compose fixes the group's lane count once (§4.2 step 3) and sends
          // it on the lane. It is stored here so the start step prints the same
          // number instead of counting the table.
          laneCount: lane.laneCount,
          prompt: lane.prompt,
          view: lane.view,
          // Whether the lane's first input is still to be sent.
          started: false,
        });
        return { ok: true, laneId: lane.laneId, sessionID };
      },
      // The group record is the adapter's own fact: the list of lane sessions,
      // written when the whole group exists and before any of them starts.
      persistGroup: async (group) => {
        const lanes = activation.laneList(group.groupId);
        if (lanes.length !== group.lanes.length) {
          throw new Error(`group ${group.groupId} has ${lanes.length} of ${group.lanes.length} lanes`);
        }
        stateOf(activation, owner).recordFissionGroup({
          groupId: group.groupId,
          owner,
          lanes: lanes.map((lane) => ({ laneId: lane.laneId, sessionID: lane.sessionID, index: lane.index })),
        });
        return { ok: true, groupId: group.groupId, lanes };
      },
      // The group's record is withdrawn when the group rolls back before it
      // ever ran. The record file is append-only, so the withdrawal is its own
      // line and a reader applies it in order.
      discardGroup: async ({ groupId }) => {
        stateOf(activation, owner).discardFissionGroup?.({ groupId });
        return { ok: true, groupId };
      },
      // §4.2 step 5: the lane's first input, after the record is durable.
      startLane: async (lane) => {
        const laneId = typeof lane === "string" ? lane : lane?.laneId;
        const record = activation.lanes.get(laneId);
        if (record === undefined) throw new Error(`lane ${laneId} is unknown`);
        if (record.started === true) return { ok: true, laneId };
        record.started = true;
        await activation.sessions.prompt({
          sessionID: record.sessionID,
          text: lanePrompt({ lane: record, view: record.view }),
          delivery: "queue",
        });
        return { ok: true, laneId };
      },
      // The old present stops producing turns; the identity lives on in the
      // lanes. The owner session is silenced, not removed.
      replacePresent: async (group) => {
        if (activation.sessions === null || typeof activation.sessions.interrupt !== "function") {
          throw new Error("the host cannot silence a session");
        }
        await activation.sessions.interrupt({ sessionID: owner });
        return { ok: true, groupId: group.groupId };
      },
      abortLanes: async ({ groupId }) => {
        const lanes = activation.laneList(groupId);
        for (const lane of lanes) {
          try {
            await activation.sessions?.interrupt?.({ sessionID: lane.sessionID });
          } catch {
            // An interrupt that fails leaves a lane session behind; the group is
            // still not registered, so nothing routes work to it.
          }
          activation.lanes.delete(lane.laneId);
        }
        return { ok: true, groupId, aborted: lanes.length };
      },
    };
    return port;
  };

  // compose's `fission` port is one object per caller: `fission(owner, prompts)`
  // is the group operation, and the five methods are the host steps it drives.
  const fissionFor = (caller) => ({
    // The table is the owner's session, not the program name: a worker program
    // call is named by its call id, while the group belongs to the session.
    fission: async (owner, prompts) => {
      const groups = activation.fissionTableOf(caller);
      return groups.fission(caller, prompts);
    },
    // The session this port belongs to. compose asks this and passes the answer
    // as `fission`'s owner, and the adapter's origin table is keyed by exactly
    // this value (the child session a `fork` created). Without it compose
    // resolved `null` and every real worker was refused `InvalidOrigin`.
    ownerName: () => (typeof caller === "string" && caller.length > 0 ? caller : null),
    ...portFor(caller),
  });
  return { forOwner: portFor, fissionFor, capacity: FISSION_CAPACITY };
};
