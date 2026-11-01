// One session's adapter surface (spec §3, §4.2, §5, §2.3, §7.3).
//
// `RoleSession` owns what one OMP session needs between tool calls: the
// `SessionState` (programs, review ledger, Scope, Pair Hint, relay fence), the
// worker lanes, the host binding, the ports, and the builder that turns one
// tool call into one `ProgramSurface`.
//
// The face a program gets is decided twice, on purpose (spec §7.3, two
// layers): the `tool_call` hook refuses a face the session's role may not own,
// and the surface built here has only that face's members.
//
// Fission (spec §4.2). The session that runs `this.fission(prompts)` is a worker
// started by `fork`, and the host can start more sessions of the same identity
// through the same SDK (`lane.mjs`). So a group is built out of real worker
// sessions, every lane created, persisted and started before the owner's present
// is touched. Replacing the present is the owner's own session taking control
// (`session-control.mjs` `silence`), which needs the owner to be between turns:
// when it is not, the step refuses (`PRESENT_BUSY`) and the composition layer
// aborts every lane of the group and registers nothing. The owner keeps its
// present and may try again — the group is never reported as replaced when it
// was not.

import { ProgramSurface, REVIEW_WINDOW_CLOSED, ROLES, SURFACE_MEMBERS } from "../compose/program/surface.mjs";
import { ComposeError } from "../compose/errors.mjs";
import { Deadline, systemClock } from "../core/clock.mjs";
import { ProgramRegistry } from "../core/program.mjs";
import { ParkTable } from "./program.mjs";
import { SessionState } from "./session-state.mjs";
import { TaskPort } from "./worker-port.mjs";
import { LaneTable, sessionFactoryOf } from "./lane.mjs";
import { WorkerRoster, workerRosterFile } from "./worker-roster.mjs";
import { SessionControl } from "./session-control.mjs";
import { NudgeBook, deliverNudge, operatorInterruptOf, turnExit } from "./nudge.mjs";
import { HostBinding } from "./host-binding.mjs";
import { ReviewMember } from "../compose/relay/review.mjs";
import { Fission } from "../compose/fission/fission.mjs";
import { feedLaneEnd as feedLaneEndToGroup } from "./fission-fanout.mjs";
import {
  execCommandPort,
  fallbackCommandPort,
  askPort,
  handOverPort,
  devopsPort,
  workerPort,
  fissionPort,
  controlPort,
} from "./ports.mjs";
import {
  ROLE,
  roleOfAgent,
  agentForRole,
  projectRoot,
  adapterEntry,
  planDraftPath,
  // The one sanitiser a session's file names share: the draft port and the
  // ledger must agree on what a session is called.
  safeSessionName,
  // The faces a lane is created with. They live in `host-facts.mjs`, not in the
  // entry module: `index.mjs` imports this file, so taking them from there would
  // close an import cycle and leave the bindings undefined while a roster is
  // being built.
  MANAGER_TOOLS,
  WORKER_LANE_TOOLS,
  isWorkerRole,
  fissionRecordFile,
} from "./host-facts.mjs";
import { readFileSync } from "node:fs";
import { readText } from "./files.mjs";
import { toPairHintMessages } from "./request.mjs";
import { RELAY_RECORD_TYPE, RELAY_PENDING_TYPE, preparedRecord } from "./relay-record.mjs";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// The Scope annotation of one tool event, wherever the host carries it.
//
// §6.2 makes the annotation a FLAT `scope` field of the call. The `tool_call`
// and `tool_result` events OMP emits carry the call's parameters as `input`, so
// `input.scope` is the documented position and is read first. The event's own
// `scope` is read second because a host may hand the annotation beside the
// parameters rather than inside them; a value in neither place is `undefined`,
// which admission refuses for every non-exempt tool — the honest answer, never
// an invented annotation.
const annotationOf = (event) => {
  const input = event?.input;
  if (input !== null && typeof input === "object" && input.scope !== undefined) return input.scope;
  return event?.scope;
};

// The session id OMP reports. `sessionManager.getSessionId()` is the durable
// one; the agent id keeps two sessions of one process apart when the manager is
// unavailable.
const SESSION_KEYS = new WeakMap();

// The key a session is registered under. It must be STABLE for one session: the
// adapter registers at bind time and tools look it up at call time, so a key
// that changed between the two would miss and silently fall back to whichever
// adapter built the definition — which is how a worker's tool came to run on
// the Manager's session.
export const sessionIdOf = (ctx) => {
  const id = ctx?.sessionManager?.getSessionId?.();
  if (typeof id === "string" && id.length > 0) return id;
  const agentId = ctx?.agent?.id;
  if (typeof agentId === "string" && agentId.length > 0) return `agent-${agentId}`;
  // Last resort, still stable per object: a counter, never a clock, so the same
  // session keeps one key for its whole life.
  if (ctx !== null && ctx !== undefined && typeof ctx === "object") {
    if (!SESSION_KEYS.has(ctx)) SESSION_KEYS.set(ctx, `anonymous-${SESSION_KEYS.size + 1}`);
    return SESSION_KEYS.get(ctx);
  }
  return "anonymous-0";
};

// Which composition role a face runs under.
export const roleForSurface = (surfaceName, sessionRole) => {
  if (surfaceName === "js-manager") return ROLES.MANAGER;
  if (surfaceName === "js-review") return ROLES.MANAGER;
  if (surfaceName === "plan") return ROLES.PLAN;
  if (surfaceName === "js-worker") return sessionRole === ROLE.DEVOPS ? ROLES.DEVOPS : ROLES.CODER;
  return ROLES.CODER;
};

// Whether a session's role may run a face at all.
//
// `js-review` is the Manager's evaluation face (spec §2.3) and it is not a
// permanent member of the role's catalog: the window decides. `window` is the
// session's own window. An ABSENT window is a SHUT window: a session that has
// none has not opened an evaluation round, and reading that as "open" admitted
// the read-only face to a Manager nobody had evaluated with. A caller that
// omits the option therefore gets "shut", and the roster check does not ask
// this function about `js-review` at all (the face is in no visible set).
// A worker never passes the role test, whatever the window says.
export const surfaceAllowed = (sessionRole, surfaceName, { window = null } = {}) => {
  if (surfaceName === "js-manager") return sessionRole === ROLE.MANAGER;
  if (surfaceName === "js-review") {
    if (sessionRole !== ROLE.MANAGER) return false;
    // No window, no evaluation: the face stays shut.
    return window !== null && window !== undefined && window.open === true;
  }
  // The planner runs `plan` and nothing else (§2.2), and `plan` is the
  // planner's alone.
  if (surfaceName === "plan") return sessionRole === ROLE.PLAN;
  if (surfaceName === "js-worker") return sessionRole === ROLE.CODER || sessionRole === ROLE.DEVOPS;
  return false;
};

export class RoleSession {
  #pi;
  #ctx;
  #role;
  #state;
  #lanes;
  // The sessions of the workers this session started, by name. A roster rebuilt
  // over a running worker adopts it from here (`LaneTable` `runningSession`).
  #laneSessions = new Map();
  #fissionLanes;
  // A lane created by a fission group is a parallel present of a forked
  // worker, not a worker a `fork` started. It may not fission again (§4.2
  // `InvalidOrigin`). The fact is stated by the creator, never re-derived.
  #fissionLane = false;
  // Worker turns that settled before any group existed (step-7 completions).
  // The live `Fission` captures the same results; this copy is what the port
  // delivers to each lane and claims once the group is registered.
  #fissionPending = [];
  #laneTools;
  #binding;
  #worker;
  #review;
  #fission;
  #views;
  #questions;
  #turns;
  #forked;
  #parks;
  #turnId;
  // The rounds already judged, so one stop produces one nudge. Whether this turn
  // asked the operator in `S1` is the state's own fact (`askedInS1`).
  #nudged = new NudgeBook();
  // The message that ended the current turn, as the host wrote it (`turn_end`).
  // It is the only place an operator interrupt is recorded on this host:
  // `stopReason: "aborted"` with `errorMessage: "Interrupted by user"`
  // (`agent-session.ts:11240`). `null` until a turn ends, and consumed by the
  // next `turnExit` so one turn's ending is judged once.
  #turnEndMessage = null;
  // 根 §2.2: this Plan started its Manager, which is the verified exit. The
  // hand-off does not move the ledger, so the fact is recorded where it happens.
  #handedOff = false;
  #api;
  #ports;
  #control;
  // Called when the round's FIRST accepted review shuts the evaluation window
  // (§2.3); a refused review leaves it open, because the round's evaluation did
  // not conclude. The adapter owns the visible set, so it supplies this; a
  // session built without one simply has no face to close.
  #onReviewAccepted;

  // `dispatch(toolName, params)` runs a host tool from inside ours. The host
  // wiring supplies it; tests pass a fake. Without it every host-tool call
  // reports `NO_HOST_DISPATCH` instead of pretending (the real host passes no
  // dispatcher; see index.mjs).
  //
  // `forked` says whether this session was started by a Herdchestra `fork`
  // (spec §4.2 `InvalidOrigin`). The Manager session never is; a worker session
  // is, when the spawn that started it came from this adapter's `fork`.
  constructor({
    pi = null,
    api = null,
    ctx = null,
    role = roleOfAgent(ctx?.agent),
    state = null,
    dispatch = null,
    forked = false,
    fissionLane = false,
    // The worker face the adapter builds: `{ workerToolsFor, workerToolNames }`.
    // `workerToolsFor(workerPi)` is called once per started worker, after that
    // worker's session exists, so the tools a worker runs read the worker's own
    // session rather than this one.
    laneTools = null,
    // The roster a WORKER session's own tools fork into. A worker is created by
    // a lane that already owns its roster, so the worker adapter is given it
    // rather than opening a second one.
    lanes = null,
    // §2.3: called when the round's first review is accepted, so the adapter can
    // close the Manager's review face. A session built without one has no face
    // to close.
    onReviewAccepted = null,
  }) {
    this.#pi = pi;
    this.#api = api;
    this.#ctx = ctx;
    this.#role = role;
    this.#forked = forked;
    this.#fissionLane = fissionLane === true;
    this.#onReviewAccepted = typeof onReviewAccepted === "function" ? onReviewAccepted : null;
    // The factory builds a session before any `ctx` exists, so the state is
    // created on the first bind. A test may pass one ready-made.
    this.#state = state ?? (ctx === null ? null : new SessionState({ sessionId: sessionIdOf(ctx), role, root: projectRoot() }));
    this.#views = new Map();
    this.#questions = [];
    this.#turns = 0;
    this.#ports = null;
    // The parked member calls of this session, keyed by program name. A park
    // leaves the body suspended on one of these promises; `unpark` settles it
    // so the original async stack continues (spec §3.3 "恢复不重跑").
    this.#parks = new ParkTable();
    this.#binding = new HostBinding({ pi, api, ctx, dispatch });
    // Workers are the host's own `task` subagents. The roster is per session, so
    // two sessions never address each other's workers by name.
    this.#lanes = lanes ?? null;
    this.#worker = null;
    this.#review = null;
    // The fission lane records must exist before `#open` builds the fission
    // port: the port closes over this map, and a map created afterwards would
    // leave the port writing into `undefined`.
    this.#fissionLanes = new Map();
    this.#laneTools = laneTools ?? null;
    this.#fission = null;
    // The control surface over this session itself (`session-control.mjs`).
    // A session this adapter did not create has none until it binds, and a
    // control surface without a session refuses every transition by name.
    this.#control = new SessionControl(ctx, { state: this.#state });
    if (ctx !== null) this.#open(ctx);
    // A worker is given its roster by the lane that created it, before it binds:
    // the roster is what its own tools fork into, so the worker's port must read
    // that table from the first call, not a second one opened later.
    else if (this.#lanes !== null) {
      this.#openWorkerPort();
    }
    // Otherwise the roster is opened by `bind`, on the session's first event.
    // The tools of this adapter already read the CALLING session's own
    // `worker` port at call time, so no second table can be consulted.
  }

  // Bind the session the host handed us on the first event. A worker session
  // created through the SDK never receives `session_start`, so this is the only
  // place its role, id and state can be learned.
  bind({ ctx, role = roleOfAgent(ctx?.agent), forked = false, fissionLane = false, root = null } = {}) {
    if (ctx === null || ctx === undefined) return this;
    if (this.#ctx === null) {
      this.#ctx = ctx;
      this.#role = role;
      this.#forked = forked;
      this.#fissionLane = fissionLane === true;
      // A state handed in at construction already carries the root its owner
      // bound; replacing it here would silently move the ledger and the scope
      // journal to `projectRoot()`. Only a session with no state yet derives one.
      //
      // `root` from the creator comes first. `projectRoot()` is ONE process-wide
      // variable, so a session that read it while another root was set wrote to
      // the wrong tree; a session the host built has no root of its own, and its
      // creator (the lane) knows which tree started it.
      this.#state = new SessionState({
        sessionId: sessionIdOf(ctx),
        role,
        root: root ?? this.#state?.root ?? projectRoot(),
      });
      this.#control = new SessionControl(ctx, { state: this.#state });
      this.#open(ctx);
      this.#ports = null;
    }
    return this;
  }

  // Bind to a session this adapter itself created (a worker started through the
  // SDK). The host emits no event for such a session, so the adapter hands over
  // the session and its identity directly. Only a session that has not bound yet
  // accepts one: rebinding would move a live session's registry and ledger.
  bindSession({ session, role = roleOfAgent(session?.agent), forked = true, fissionLane = false, root = null } = {}) {
    if (session === null || session === undefined) return this;
    if (this.#ctx !== null) return this;
    // The session's own view. `agent`, `sessionManager` and the getters a
    // surface reads live on the session itself, so the session IS the context.
    return this.bind({ ctx: session, role, forked, fissionLane, root });
  }

  // The session-scoped objects: lanes, worker port, review member, fission.
  #open(ctx) {
    // Cold-start half of the relay (root §2.4 第 3 步). A record committed by an
    // earlier process is the durable fact: the fence, the successor tenure and
    // the milestone are rebuilt from it BEFORE any request can be projected, so
    // a restarted successor hides the previous conversation from its very first
    // request. The transcript wins over the ledger file, because the transcript
    // is the copy the host published atomically.
    const record = this.#state?.relayRecordIn(ctx?.sessionManager ?? ctx) ?? null;
    if (record !== null) this.#state.adoptRelayRecord(record);
    // The adapter's own journal is the second durable view (root §1.3). It
    // answers when the transcript cannot: after a relay whose switch ran at a
    // sealed boundary, the successor's copy could only be kept there. The
    // transcript still wins when both exist — it is the copy the host published.
    else {
      const retained = this.#state?.latestRelayRecord?.() ?? null;
      if (retained !== null) this.#state.adoptRelayRecord(retained);
    }
    // A relay ASKED FOR but never performed. The intent was published before
    // the turn that asked for it ended, so a process that died inside that turn
    // leaves it on disk; adopting it here means the obligation survives the
    // restart and the first idle boundary discharges it. An intent whose record
    // already exists was performed, so it is dropped rather than replayed.
    const manager = ctx?.sessionManager ?? ctx;
    const pending = this.#state?.pendingRelayIn(manager) ?? null;
    if (pending !== null) {
      // The record is what says whether the obligation still stands, and WHICH
      // copy it is says it exactly:
      //
      //   - a record marked `retiredTranscript: true` sits in the transcript the
      //     relay retires. It was published in the turn, and the switch has not
      //     run — that is the crash this intent exists to survive, so the
      //     obligation STANDS and the first idle boundary performs it;
      //   - the successor's own copy (`retiredTranscript: false`) means the
      //     switch ran. The intent was discharged, and replaying it would open
      //     a second successor for one relay.
      //
      // Tenure is NOT the test. Every pre-switch record names the tenure the
      // relay OPENS (`pending.tenure + 1`), so `record.tenure > pending.tenure`
      // is true for the record the turn just published — reading it as "already
      // switched" drops the obligation at exactly the crash it exists for.
      const carried = preparedRecord(pending);
      // `record` is the transcript's view, or the adapter's own when the
      // transcript has none. Either way, the successor's copy
      // (`retiredTranscript: false`) is what says the switch ran.
      const governing = record ?? this.#state?.latestRelayRecord?.() ?? null;
      const discharged =
        governing !== null &&
        governing.retiredTranscript !== true &&
        carried !== null &&
        governing.tenure === carried.tenure &&
        governing.notice === carried.notice;
      if (discharged) this.#state.clearRelayPending();
      else this.#state.adoptRelayPending(pending);
    }
    // The successor the LAST tenure named, when this transcript has already
    // switched. `newSession` returns before the host re-reports the id, so the
    // live manager may still answer with the outgoing id; the durable header of
    // the successor names its own id, and that is the one this tenure writes
    // under. Without this, a relay whose commit is retried after the switch (a
    // crash between the reset and the record's re-commit) names the successor
    // after the transcript it replaced.
    const successorId = record?.successorSessionId ?? null;
    if (typeof successorId === "string" && successorId.length > 0) this.#state.adoptSessionId(successorId);
    // A worker is a session this adapter creates and drives through the SDK
    // (`lane.mjs`): the host's `task` path is unreachable from an extension, and
    // a session created through the SDK never gets its extension runtime
    // initialized, so the worker face is fixed at creation and the lane
    // authorizes it before the first turn.
    // Every session opens its OWN roster here, a worker included: a worker's
    // `fork` fills the worker's table and its `join` reads that same table, so
    // its children are its own and its children's parent is the worker — not
    // the session that started it. Sharing the starter's table would put the
    // worker's children in the Manager's roster.
    if (this.#lanes === null) this.#lanes = this.#newRoster(ctx);
    this.#openWorkerPort(ctx);
    // Fission (spec §4.2) belongs to the session that `fork` started — a worker
    // — and the check is made on the calling session, not on a class of session.
    // `origins` must therefore accept THIS session when it was forked, which is
    // exactly the case a "workers cannot fission" shortcut would exclude.
    this.#fission = new Fission({
      port: fissionPort({
        lanes: this.#fissionLanes,
        startLane: async (request) => {
          const started = await this.#lanes.start({ ...request, register: false, depth: this.#laneDepth() });
          // Remembered even though the group owns the lane: a roster rebuilt
          // after a reload hands a still-running worker back to a lane through
          // this map, and a lane of an unconverged group is still a turn.
          if (started.ok === true) this.#laneSessions.set(request.name, started.lane.session);
          return started;
        },
        // A lane runs a WORKER definition. The owner may be a Manager (§4.2
        // gives fission to a forked worker, but the port is built for every
        // session), and `agentForRole` now refuses a non-worker role so that a
        // `role: plan` brief cannot be forked. Map the owner to a worker here
        // rather than weakening that refusal.
        agentFor: () => agentForRole(isWorkerRole(this.#role) ? this.#role : ROLE.CODER),
        owner: () => this.name(),
        // §4.2 steps 2–3: the owner's last delivered assistant text. The caller
        // IS the owner's own session, so its transcript is readable here; only
        // a session that exposes no transcript leaves the view empty.
        view: () => this.#ownerView(),
        // Replacing the present is this session's own control: it aborts its
        // live turn and drops its queue. The wait for the owner to be between
        // turns would be a wait for the call that is asking, so the port does
        // not wait at all: it marks the present, drops the queue, and arms the
        // host's own abort for the end of the turn that called `fission`.
        present: this.#control,
        // §4.2 steps 7–10: the lanes this port builds end their turns in THIS
        // session's group, and compose decides what each end means. The port
        // asks for the live object on every call, so a group admitted after the
        // port was built is still found.
        fission: () => this.#fission,
        // §4.2 step 4: the group's creation is committed before any lane runs.
        // One durable line in the session's own journal, so a crash after the
        // build still leaves the group recoverable.
        recordFile: () => fissionRecordFile(this.#state.root, this.#state.lineageId),
        // §4.2 step 7: the completions that settled before this group. The port
        // takes them when the first lane is built (the same cut compose made)
        // and delivers/claims them once the group exists.
        takeCompletions: () => this.#takeFissionPending(),
        onGroupBuilt: ({ groupId, captured }) => this.#scheduleFissionDelivery(groupId, captured),
        fanout: {
          feedLaneEnd: (args) => feedLaneEndToGroup({ ...args, onEvent: (entry) => this.#noteFission(entry) }),
        },
      }),
      // The `owner` that reaches this port is a PROGRAM name (the tool call id,
      // `ProgramSurface` passes `this.name`), not the session's identity. The
      // admission question is about the SESSION: was this session started by a
      // Herdchestra `fork`? Answering it by comparing a program name to the
      // session name refused every real worker (`w1` vs `call_96a5…`).
      //
      // So the check names the fact it tests and does not look at the argument:
      // the port belongs to one session, and `#forked` is that session's own
      // creator fact (carried by `bindSession`, never re-derived). The generic
      // `Fission` keeps its `origins(owner)` contract untouched, so no other
      // host changes behaviour.
      // A fission lane is itself a parallel present: §4.2 refuses a nested
      // group (`InvalidOrigin`). A `fork`-started worker is the only origin.
      origins: () => this.#forked === true && this.#fissionLane !== true,
      // The host's own way to start a session: this adapter's roster
      // (`LaneTable`, which creates the worker face, wires the runtime,
      // authorizes the worker and starts its first turn). `canStartWorker()`
      // alone is not capacity — it only reports that an SDK factory exists.
      capacity: (laneCount) => laneCount >= 2 && sessionFactoryOf(this.#api) !== null,
    });
    // A roster rebuilt over a running group lane (`#newRoster`) adopts it from
    // this map; the groups live on the session, not on the table.
    this.#rebuildLaneSessions();
    // Review and relay are the Manager's: a worker has no ledger of its own, so
    // `ports()` hands a worker only the members it owns.
    if (this.#forked) return;
    this.#review = new ReviewMember({
      ledger: this.#state.ledger,
      // The same port `ports().relay` exposes. A relay never succeeds on this
      // host (see `handOverPort`), so the ledger keeps the incumbent; the
      // review still records obligations and still advances a Plan's milestone.
      port: {
        // The session's OWN hand-off, not the bare port: `SessionState.handOver`
        // opens the successor in the ledger and installs the fence after the
        // record is durable. Wiring the port directly here was how a successful
        // commit left the ledger at the old tenure with no fence.
        //
        // The DECISION is forwarded whole: it carries the findings that emptied
        // the ledger, and a deferred relay must record them.
        handOver: (decision) => this.handOver(decision),
        startManager: (draft) => this.startManager(draft),
        askOperator: (question) => this.askOperator(question),
      },
    });
  }

  // The depth a session this one starts must carry: this session's own depth
  // plus one. The host reports the depth on `ctx.agent` (`sdk.ts:3571`); a
  // session with no reading is top-level, so its child is 1. This is a FACT
  // about nesting, not a cap: the host's `task.maxRecursionDepth` is applied
  // when it builds a task tool, and a session created through the SDK has none.
  #laneDepth() {
    const depth = this.#ctx?.agent?.depth;
    return (typeof depth === "number" && Number.isFinite(depth) ? depth : 0) + 1;
  }

  // One line per fission fact, only when HERDCHESTRA_DEBUG=1: a real-host run
  // then shows which lane delivered, which one announced, and whether the group
  // had already closed. Silent by default — a working session must not write to
  // its own transcript.
  #noteFission(entry) {
    // §4.2 step 10: the group converged. The takeover lane's announcement is
    // the merged result, and the owner becomes the single present again — its
    // old present was only replaced while the group ran. The result is queued
    // in the owner's roster so the Manager's `join()` receives it.
    if (entry?.converged !== null && entry?.converged !== undefined) {
      const receipt = entry.converged;
      const result = receipt?.result ?? null;
      const text = typeof result === "string" ? result : result?.text ?? null;
      // Restoring is a fact of this session's control surface; a surface that
      // cannot restore (a test double) simply reports it.
      const restored = this.#control?.restore?.() ?? null;
      this.#lanes?.deliver?.(this.name(), {
        text,
        agentId: this.name(),
        role: this.#role,
        error: result?.error ?? null,
      });
      if (process.env.HERDCHESTRA_DEBUG === "1") {
        console.log("[herd] fission converged", JSON.stringify({ groupId: receipt?.groupId ?? null, restored }));
      }
      return;
    }
    if (process.env.HERDCHESTRA_DEBUG !== "1") return;
    console.log("[herd] fission", JSON.stringify(entry));
  }

  // §4.2 step 7: deliver each captured completion to every lane once, and claim
  // it in the group. Compose already broadcast the same completions when the
  // group became live; the claim is what makes `ConvergenceReady` require every
  // lane to have received them. The work runs one macrotask after the build
  // steps so the group is registered in `Fission` first.
  #scheduleFissionDelivery(groupId, captured) {
    if (!Array.isArray(captured) || captured.length === 0) return;
    setTimeout(() => {
      const fission = this.#fission;
      const owner = this.name();
      const group = fission?.groupOf?.(owner) ?? null;
      if (group === null || group.groupId !== groupId) return;
      for (const lane of group.lanes) {
        const record = this.#fissionLanes.get(lane.laneId) ?? null;
        if (record === null) continue;
        for (const completion of captured) {
          const key = completion?.key ?? completion;
          try {
            fission.claim(owner, lane.index, key);
          } catch {
            // A group that already closed refuses the claim; its terminal
            // write is the fact that matters, not this late delivery.
            continue;
          }
          const text = typeof completion?.text === "string" ? completion.text : "";
          if (text.length > 0 && record.lane.settled !== true) {
            void record.lane.steer(text).catch(() => {});
          }
        }
      }
    }, 0);
  }

  // This session's own roster. `fork` and `join` of one session read exactly
  // this table, and no other session can reach it.
  #newRoster(ctx = null) {
    // A rebuilt roster REPLACES the old table for this session. The old table is
    // detached: its lanes stop listening to their sessions (the successor adopts
    // them through `runningSession`, below), and a `join` already waiting on it
    // is released with the disposal refusal. Without this a caller inside `join`
    // when the reload happened waited for a turn whose events now reach the new
    // table — the hang, and the "nothing to collect" lie, this journal exists to
    // stop. The workers themselves are untouched: one session, one Lane, the
    // successor's.
    if (this.#lanes !== null) this.#lanes.detach();
    // The live sessions are KEPT across a rebuild. The journal says which
    // workers are still RUNNING, and the rebuilt table adopts exactly those
    // through `runningSession`; clearing this map first left the adoption
    // lookup empty, so a journal `RUNNING` entry could never be handed back to
    // its real turn and `join` waited forever. A finished worker's session is
    // not adopted (its journal entry is FINISHED), and a later `fork` of the
    // same name overwrites the entry.
    // The roster is DURABLE. A worker is a session that outlives the adapter
    // that started it (a plugin reload, a restart, a second extension
    // instance), so the facts `join` decides on live in
    // `.Herdchestra/<session>.workers.jsonl` and are replayed here. Without
    // this, every `join` after a reload answered "nothing to collect" while the
    // worker sessions were still running (measured on the OpenCode sibling,
    // 2026-11-01). A session with no state yet (a factory-time table) keeps the
    // in-memory roster: there is no session id to name a file after.
    const roster =
      this.#state === null
        ? null
        : new WorkerRoster({ file: workerRosterFile(this.#state.root, this.#state.sessionId) });
    const table = new LaneTable({
      api: this.#api,
      extensionPaths: [adapterEntry()],
      parentAgentId: ctx?.agent?.id ?? null,
      taskDepth: (typeof ctx?.agent?.depth === "number" ? ctx.agent.depth : 0) + 1,
      roster,
      // A worker this process still runs is handed back to the rebuilt roster,
      // so a reload waits on the real turn instead of on a `settle` that
      // belongs to the process which ended.
      runningSession: (name) => this.#laneSessions.get(name) ?? null,
      // The face follows the ROLE of the session a lane will hold, not the
      // adapter's own face. A Plan hand-off starts a MANAGER session through
      // this roster (spec §6.1), and that session must be created with
      // `js-manager`, never `js-worker`. This default holds even when the
      // adapter passed no `laneTools` at all — a session built by hand (a test,
      // or a host that wired nothing) would otherwise create lanes with no
      // `toolNames` and let the host decide the face.
      toolNamesFor: (laneRole) => (laneRole === ROLE.MANAGER ? [...MANAGER_TOOLS] : [...WORKER_LANE_TOOLS]),
      ...(this.#laneTools === null ? {} : this.#laneTools),
      // A worker's turn that ends while no group exists is a step-7
      // completion: the next fission broadcasts it to every lane. The live
      // `Fission` captures it, and this session keeps a copy so the port can
      // deliver and claim it once the group is registered.
      onArrive: (result) => {
        this.#fissionPending.push(result);
        // The same bound compose keeps: a session that never fissions must not
        // grow a queue of every worker turn it ever ran.
        if (this.#fissionPending.length > 256) this.#fissionPending.splice(0, this.#fissionPending.length - 256);
        void this.#fission?.settled?.(result);
      },
    });
    return table;
  }

  // The pre-fission completions this session has seen but not yet delivered to
  // a group. `fissionPort.buildLane` takes them when a group's first lane is
  // built, which is the same cut compose made when it captured its own list.
  #takeFissionPending() {
    const pending = this.#fissionPending;
    this.#fissionPending = [];
    return pending;
  }

  // The owner's last delivered assistant text, or null when its transcript
  // exposes none. §4.2 steps 2–3 give every lane the owner's view; the caller
  // here is the owner's own session, so reading its own entries is not reading
  // a foreign transcript. Only assistant TEXT is a delivered view: a tool call
  // or thinking block is not what the owner said.
  #ownerView() {
    const entries = this.#sessionManagerEntries();
    if (entries === null) return null;
    for (let index = entries.length - 1; index >= 0; index -= 1) {
      const entry = entries[index];
      const message = entry?.type === "message" ? entry.message : entry;
      if (message === null || typeof message !== "object" || message.role !== "assistant") continue;
      const content = message.content;
      const text =
        typeof content === "string"
          ? content
          : Array.isArray(content)
            ? content
                .filter((part) => part?.type === "text" && typeof part.text === "string")
                .map((part) => part.text)
                .join("")
            : "";
      if (text.length > 0) return text;
    }
    return null;
  }

  // The live sessions of the fission groups this process still holds, by lane
  // id. A rebuilt roster adopts a running lane through this map, so a reload
  // inside one process keeps waiting on the real turn.
  #rebuildLaneSessions() {
    if (this.#fission === null) return;
    for (const group of this.#fission.groups()) {
      for (const lane of group.lanes) {
        const record = this.#fissionLanes.get(lane.laneId) ?? null;
        if (record !== null) this.#laneSessions.set(lane.laneId, record.lane.session);
      }
    }
  }

  // The worker port over this session's own roster. `join` waits on the roster
  // itself, so a worker's `join` collects the children its own `fork` started.
  #openWorkerPort(ctx = null) {
    this.#worker = new TaskPort({
      lanes: this.#lanes,
      sleep,
      // The sessions of the workers this process still runs. A roster rebuilt
      // over a running worker adopts it through this lookup, so a reload keeps
      // waiting on the real turn instead of on a `settle` that belongs to the
      // process which ended. The map is shared with the port's roster by
      // reference, so a session recorded after the roster was built is still
      // found.
      sessions: this.#laneSessions,
      // The ROLE compose resolved from the brief, mapped to a host definition.
      // Never the worker's name: §2.1 makes identity an explicit fact, and a
      // name is not one.
      agentFor: (role) => agentForRole(role),
      // A worker's session carries no `getAsyncJobSnapshot`; `join` on a worker
      // reads its own roster instead, and a missing snapshot is simply none.
      snapshot: () => (typeof ctx?.getAsyncJobSnapshot === "function" ? ctx.getAsyncJobSnapshot() : null),
      // A child of THIS session is one level deeper than this session. Read per
      // call, because the session's depth is the host's fact and the adapter
      // refreshes its context on every event.
      depth: () => this.#laneDepth(),
    });
    return this.#worker;
  }

  get role() {
    return this.#role;
  }

  // Adopt the context of the event now being handled.
  //
  // The host builds a new `ExtensionContext` per emit (`runner.ts`
  // `createContext`), and each carries that moment's live `sessionManager`,
  // `agent`, `isIdle()` and `abort`. Holding the `session_start` one forever
  // left every later hook reading a context whose members were already gone —
  // measured live: inside a `js-manager` call, `ctx.sessionManager` was
  // `undefined`, which silently degraded Scope admission and made the deferred
  // relay unable to commit.
  //
  // Only the context is replaced. The state, roster, ledger, programs and parks
  // are this session's own and must not move; the control surface is rebuilt
  // because it is a thin wrapper whose whole job is to read the live session.
  refreshContext(ctx) {
    if (ctx === null || ctx === undefined || typeof ctx !== "object") return this;
    this.#ctx = ctx;
    // The wrapper is REBOUND, never replaced: the fission port and the ask
    // path hold this object, and a new instance would leave them reading the
    // `session_start` context's `abort`/`isIdle` forever.
    if (this.#control !== null && typeof this.#control?.attach === "function") this.#control.attach(ctx);
    else this.#control = new SessionControl(ctx, { state: this.#state });
    // `binding` carries the same context for the ports that ask it for the host
    // dialog; refreshing it keeps `ask` on the live UI.
    if (this.#binding !== null && typeof this.#binding.refresh === "function") this.#binding.refresh(ctx);
    return this;
  }

  // The session this adapter serves, or null before binding. A lane compares it
  // with the session it created, so a worker's tools can never act on another
  // session.
  get ctx() {
    return this.#ctx;
  }

  get state() {
    return this.#state;
  }

  get worker() {
    return this.#worker;
  }

  get lanes() {
    return this.#lanes;
  }

  get binding() {
    return this.#binding;
  }

  get questions() {
    return [...this.#questions];
  }

  get turn() {
    return this.#turns;
  }

  // What this session's terminal turn end means (根 §2.2). The rule is
  // compose's; this reads the session's own facts — its role, its ledger, and
  // whether this turn asked the operator in `S1` — and books the round once.
  //
  // A worker session has no ledger of its own (its Manager joins the record),
  // and `evaluateTurnExit` answers `not-user-facing` for it, so the call costs
  // one property read on an ordinary worker turn.
  // The message the host ended this turn with (`turn_end.message`), or null.
  noteTurnEnd(message) {
    this.#turnEndMessage = message !== null && typeof message === "object" ? message : null;
    return this.#turnEndMessage;
  }

  get turnEndMessage() {
    return this.#turnEndMessage;
  }

  turnExit(event = null) {
    const turnId =
      typeof event?.turnIndex === "number"
        ? `turn-${event.turnIndex}`
        : typeof this.#turnId === "string" && this.#turnId.length > 0
          ? this.#turnId
          : `turn-${this.#turns}`;
    const ledger = this.#state?.ledger ?? null;
    // The host's own record of how this turn ended. An operator interrupt is
    // never nudged (`compose/nudge.mjs` `operatorInterruptOf`): the person who
    // pressed stop is the one the standing order says the model never waits for.
    const endedWith = this.#turnEndMessage;
    this.#turnEndMessage = null;
    // Read ONCE, here, where the host's record lives: the host's own interrupt
    // spelling is a host fact, and the shared rule is asked with the answer.
    const interrupted = operatorInterruptOf(endedWith);
    const decision = turnExit({
      role: this.#role,
      ledger,
      handedOff: this.#handedOff,
      askedInS1: this.#state?.askedInS1 !== null && this.#state?.askedInS1 !== undefined,
      interrupted,
      turnId,
      nudged: this.#nudged,
    });
    // The question belongs to the turn that asked it, and this evaluation is
    // that turn's end. It is consumed WHATEVER the verdict: the host may report
    // the ask's own abort as an operator interrupt (`turn_end` says
    // "Interrupted by user"), and leaving the marker set then masked the NEXT
    // stop as another `ask-s1` — the session was never nudged again.
    //
    // The turn is booked in the same step, so a re-emitted `agent_end` for the
    // same turn cannot nudge after a legal stop consumed the marker.
    this.#nudged.book(decision.turnId);
    this.#state?.clearAskedInS1?.();
    return decision;
  }

  // Deliver a nudge through the host's own queue (`pi.sendUserMessage` with
  // `deliverAs: "followUp"`), so it arrives as the session's next input rather
  // than as a tool result in a turn that already ended.
  async nudge(text) {
    const send = this.#pi?.sendUserMessage;
    return deliverNudge({ send: typeof send === "function" ? send.bind(this.#pi) : null, text });
  }

  get fission() {
    return this.#fission;
  }


  // The deadline of the program whose member call is running. `fission` needs
  // it to bound its wait for the owner to reach the end of the turn (spec §3.3
  // fixes one deadline per tool call; §4.2 adds none of its own). The registry
  // holds every live program, and a `fission` call can only come from one of
  // them, so the running one is found by state: a program that is `running` or
  // `waiting` and carries a deadline. When none is found the answer is null and
  // the wait degrades to a single check rather than to an invented timer.
  #currentDeadline() {
    if (this.#state === null) return null;
    let found = null;
    for (const program of this.#state.programs.list()) {
      if (program.deadline === null || program.deadline === undefined) continue;
      if (program.state !== "running" && program.state !== "waiting") continue;
      // The most recently started program wins: a nested call belongs to the
      // innermost running program, and the registry lists in start order.
      found = program.deadline;
    }
    return found;
  }

  // This session's own control surface: the transitions a tool may perform on
  // the session it runs in (`session-control.mjs`).
  get control() {
    return this.#control;
  }

  // The parked member calls of this session, keyed by program name.
  get parks() {
    return this.#parks;
  }

  // This session's identity: the OMP agent id, which is also the `agent://`
  // handle. It names the SESSION, never a program: `ProgramSurface` names a
  // program after its tool call id, and the two are unrelated. The fission port
  // uses this as the group owner's label; admission is decided by the session's
  // own `forked` fact, not by comparing names.
  name() {
    // The agent id, from whichever view the session offers. A session created
    // through the SDK answers `getAgentId()` and may carry no `agent.id` at all,
    // so both are read before the session id.
    const id = this.#ctx?.agent?.id;
    if (typeof id === "string" && id.length > 0) return id;
    const got = typeof this.#ctx?.getAgentId === "function" ? this.#ctx.getAgentId() : null;
    if (typeof got === "string" && got.length > 0) return got;
    return this.#state.sessionId;
  }

  // The turn number of the current request (spec §6.2: bookkeeping is per
  // model turn). `before_agent_start` bumps it once per request.
  nextTurn() {
    this.#turns += 1;
    return this.#turns;
  }

  // The ports a surface reads. `command` prefers the host's own executor and
  // falls back to node when no host is bound (tests, print mode). `ask` is the
  // question's host side (`askPort`): the surface parks the program first, and
  // this port records the question and answers with the asked receipt, whose
  // `interrupt: true` makes the tool call hand control back to the operator.
  //
  // `relay` and `ledger` are this session's, not the surface's own: the review
  // ledger outlives one program (it is persisted per session) and the
  // hand-over it may trigger belongs to the session, not to a request.
  // `relay` is the session's own `review` entry point, so a `review` made from
  // inside a program persists the ledger exactly like one made through the
  // session's API; the port's three named steps are still what it performs.
  //
  // The record is built once per binding and reused: `runProgram` binds the
  // members to one instance, and a tool registered at factory time holds this
  // session before the first event — building the ports lazily on the first
  // read is what lets such a tool work without a `session_start`.
  ports() {
    if (this.#ports !== null) return this.#ports;
    if (this.#state === null || this.#worker === null) {
      throw new Error("NO_SESSION_CONTEXT: this session has not started yet");
    }
    // A worker owns no review member (its ledger is the Manager's job), so the
    // ports it reads are its own: command, control, fork and the roster.
    const worker = this.#review === null;
    this.#ports = {
      command: this.#pi?.exec === undefined ? fallbackCommandPort : execCommandPort(this.#pi),
      control: controlPort(this.#state, this.#parks),
      // compose reads `ports.fork.fork` / `ports.fork.join` (surface.mjs:307,
      // :309), so the key is `fork`, not `worker`. A fixture that used `worker`
      // hid this in the offline tests while the real host failed on the first
      // `fork(...)` call.
      fork: workerPort(this.#worker),
      ...(worker
        ? {}
        : {
            relay: {
              // The port reaches the session's own `review` method, not a copy of
              // it: that is what books the evaluation window and runs the
              // visible-set callback, so a program's bare `review(...)` closes the
              // round's face exactly as the session's own review does.
              //
              // A refused review is a RECEIPT, never a rejected promise.
              //
              // A program reaches this as the primitive `review(findings)` and
              // compose calls it without a guard of its own. The session method
              // is async, so a refused argument list became a rejected promise:
              // a body that did not await it (legal — the value may be read
              // later, and a member call may park) left it unobserved, and the
              // host aborted with `[Unhandled Rejection]` and exit 1 (measured
              // on 18.14.2, `/tmp/probe2`: `review([{"id": ...}])`). The tool
              // call was never answered.
              //
              // The refusal keeps the shape every other review answer has, so a
              // program reads `receipt.ok === false` whatever happened, and the
              // reason travels where the model already looks for one.
              review: async (findings, source = null, callId = null) => {
                try {
                  return await this.review(findings, source, callId);
                } catch (error) {
                  const code = typeof error?.code === "string" ? error.code : String(error?.name ?? "ERROR");
                  const message = error instanceof Error ? error.message : String(error);
                  return { ok: false, code, message, ...(error?.details === undefined ? {} : { details: error.details }) };
                }
              },
              // The session's OWN hand-off, not the bare port: `SessionState.handOver`
              // opens the successor in the ledger and installs the fence after the
              // record is durable. The decision travels whole (see above).
              handOver: (decision) => this.handOver(decision),
              startManager: (draft) => this.startManager(draft),
              askOperator: (question) => this.askOperator(question),
            },
          }),
      ...(worker ? {} : { devops: devopsPort({ binding: this.#binding }) }),
      fission: this.#fission,
      ledger: this.#state.ledger,
      // `this.ask(question)`: park (already done by the surface) + interrupt.
      // The answer is not returned here — it arrives later as
      // `this.unpark(name, answer)` from another program of this session.
      ask: { ask: (question, options) => this.ask(question, options) },
      // The two exempt snapshot queries (§6.2). `ScopeHooks#ask` is synchronous
      // and answers from the newest committed facts; a refusal (a book
      // mid-settle, an unknown member) travels as the value it is.
      scope: { ask: (member) => this.#state.scopeHooks().ask(member, this.#state.sessionId) },
      // Plan's draft (spec §6). The surface hands this object to compose, and
      // compose's `review` reads it for a Plan hand-off (根 §2.2): the bytes are
      // the SAVED draft, and an absent draft reads as the empty string so core
      // refuses the hand-off with `DRAFT_MISSING` before a Manager session
      // exists, rather than starting one from nothing.
      //
      // This port is the ONLY reader. `review(findings, source)` is the surface
      // member and reads it here; the host method of the same name takes the
      // bytes a caller already read, and never reaches for the file itself.
      //
      // A Manager session is a separate session with its own id, so its draft
      // path names a file it never wrote and reads empty — the port is
      // harmless there. The path is derived per read, not captured, so a
      // session that switches records still names the right file.
      ...(worker ? {} : {
        draft: {
          // The directory is prepared by `SessionState` at construction, and a
          // preparation that failed is a fact (`draftDirFailure`), not something
          // to retry here: naming the path must not create anything, because a
          // plain file where the directory belongs would throw instead of
          // letting the session say why it cannot write.
          // The LINEAGE id, not the live one: a relay adopts the successor's
          // session id (§2.4 第 3 步), and a draft named by the live id moved to
          // a file nobody had written, so every hand-off after a relay was
          // refused `DRAFT_MISSING`. The participant is the same across a
          // relay, so its one artifact keeps one name.
          path: () => planDraftPath(this.#state.root, safeSessionName(this.#state.lineageId)),
          // A draft directory that could not be prepared is NOT an absent
          // draft. Reading it as `""` would turn "this Plan cannot write" into
          // "this Plan wrote nothing", and the hand-off would be refused for
          // the wrong reason — with the real cause (`ENOTDIR`, a read-only
          // root) invisible. `null` says "cannot tell", which core refuses.
          read: () => {
            if (this.#state.draftDirFailure !== null) return null;
            return readText(planDraftPath(this.#state.root, safeSessionName(this.#state.lineageId))) ?? "";
          },
        },
      }),
    };
    return this.#ports;
  }

  // One `ProgramSurface` for one tool call. The surface's role is the face the
  // tool names, never the session's own role.
  // `intent` fixes the request's deadline at admission (spec §3.3); the
  // registry is handed over so a settled run retires its own name.
  //
  // The returned record carries the surface, the program, and the session's
  // park table: the tool needs all three (the receipt, the lifecycle, and the
  // promise a park leaves pending).
  buildSurface({ toolCallId, surfaceName, source, intent = null }) {
    const state = this.#state;
    const name = toolCallId;
    // §2.3: a `js-review` program runs only through an open evaluation window.
    // The tool gate refuses one too; this is the same refusal at the build, so a
    // caller that skips the gate cannot read the tree through a shut window.
    //
    // The BUILD does not open the round. `before_agent_start` is the request
    // boundary, and it is the only place a round opens: opening here made a
    // build REOPEN a window a review had just closed — the round's first
    // accepted review was meant to shut the face for the rest of that round, and
    // this call handed it back.
    if (surfaceName === "js-review" && !surfaceAllowed(this.#role, surfaceName, { window: state.window })) {
      throw new ComposeError(REVIEW_WINDOW_CLOSED, "the evaluation window is closed", {
        role: this.#role,
        state: state.window.state,
      });
    }
    // A program name belongs to the session, not to one call: a later call may
    // name a parked program to resume it, and `unpark` requires the controller
    // to have observed the target in this request (spec §3.3). The call
    // therefore observes every live program of the session before its own.
    const program = state.programs.begin(name, {
      // The registry stores a `Deadline`, not a number: it reads
      // `remainingMs()` from it. The budget for an intent is computed once, in
      // `Deadline.forIntent`.
      deadline: intent === null ? null : Deadline.forIntent(systemClock, intent),
      // Core §3.3: a controller must hold every capability the TARGET program
      // carries, and the target's set is a fact of the program, not a parameter
      // of `unpark` (core/program.mjs `ProgramState#capabilities`). A program is
      // registered with the role its face runs as, so two programs of one
      // session share the set and `unpark` passes exactly when they belong to
      // the same session and role. Without this the set would be empty, every
      // controller would "hold" it, and the check would mean nothing.
      capabilities: [roleForSurface(surfaceName, this.#role)],
    });
    state.openRequest(name);
    for (const live of state.programs.list()) {
      if (live.name === name) continue;
      state.observe(name, live.name);
    }
    const surface = new ProgramSurface({
      name,
      role: roleForSurface(surfaceName, this.#role),
      program,
      clock: systemClock,
      // 根 §3.3: a receipt's `source` is the full line-numbered program, so this
      // build names the program it runs. Compose refuses a surface without one
      // (`PROGRAM_SOURCE_REQUIRED`), which is what stops a park receipt from
      // answering `source: null` on a host whose tool forgot to pass it.
      source,
      ports: this.ports(),
      root: state.root,
      views: this.#views,
      registry: state.programs,
      // §2.3: the SESSION's evaluation window, not a fresh one per program. The
      // gate that must hold is compose's — a review accepted by one program
      // keeps `js-review` shut for the next — and it can only hold when the
      // surface reads the window the session keeps. An adapter-side
      // `surfaceAllowed` beside it is a second gate, never the one §2.3 names.
      window: state.window,
      // A program's bare `review(...)` changes the SESSION's ledger, so the
      // surface must be able to persist it. Without this the review was applied
      // in memory and never written, and a later session read an empty ledger.
      ledgerStore: { save: () => this.#state.saveLedger() },
    });
    // §2.3: a PROGRAM's bare `review(...)` books the session window through
    // `ProgramSurface.noteReview` — compose's `ReviewMember` never calls the
    // port's `review`, so the callback wired there alone never fired. Wrap the
    // one method that actually closes the window, so the adapter learns of the
    // round's first accepted review exactly as it does on the session path.
    const noteReview = surface.noteReview.bind(surface);
    surface.noteReview = (receipt) => {
      const before = state.window.open;
      const answer = noteReview(receipt);
      if (before === true && state.window.open === false && this.#onReviewAccepted !== null) {
        this.#onReviewAccepted(receipt);
      }
      return answer;
    };
    // The expiry's park channel (根 §3.3: 一旦到期). `release` settles the parked
    // expression of a program the expiry suspended, so a body blocked on a
    // member answers the park receipt instead of hanging on a request that ran
    // out of time; `programs` is the request's live set, which this session
    // keeps in its registry.
    const release = (suspended) => {
      this.#parks.settle(suspended.name, null);
    };
    return {
      surface,
      program,
      parks: this.#parks,
      // §6.1 item 7: the Pair Hint's Idle Hint lists unsettled park handles.
      // A handle enters the list when its program parks and leaves it when the
      // resumed body finishes (program-tools attaches the outcome callback).
      noteParked: () => state.noteParked(name),
      noteFinished: () => state.noteFinished(name),
      // The host half of §3.3's deadline: the tool races the program against
      // it, and on expiry the controller and every still-active program of the
      // request park together.
      expire: () =>
        surface.expire({
          programs: state.programs.list(),
          release,
        }),
    };
  }

  // Close the request a program tool call opened. The observation ledger is per
  // request (spec §3.3), so it never outlives the call that reads it.
  closeSurface(name) {
    this.#state.closeRequest(name);
  }

  // A finished program releases its parked call: a body that ended cannot be
  // resumed, and a promise left pending would keep the program's memory alive
  // for the life of the session. A parked program keeps its entry for the
  // resume, so this is called with the program's state.
  releasePark(name, state) {
    if (state === "parked") return false;
    return this.#parks.close(name);
  }

  // `review` for the surface: apply findings, perform the side effects the
  // receipt names, persist the ledger. A relay never succeeds on this host
  // (`handOverPort` refuses), so `ReviewMember` leaves the ledger with the
  // incumbent and this save writes exactly that tenure.
  //
  // `source` is the canonical saved draft, read by compose through the `draft`
  // port for a Plan hand-off (spec §2.2). The review carries no text of its
  // own, so this argument is the only thing a Manager can start from.
  // `callId` is the host's tool-call identity (根 §2.4 第 6 步): a replayed call
  // returns the receipt it produced the first time, and neither relays nor
  // persists a second time.
  async review(findings, source = null, callId = null) {
    // §2.3: the round's first ACCEPTED review shuts the evaluation window, and
    // the window is the session's own. `ProgramSurface.noteReview` is the one
    // owner of that booking for a PROGRAM's review; this path is the session
    // member's own review (the `review` a host tool exposes outside a program),
    // and it reaches no `ProgramSurface`, so it books here. Booking twice is a
    // no-op — a window closes once per round — which is why the same line is
    // safe on both paths.
    const window = this.#state.window;
    const before = window.open;
    const receipt = await this.#review.review(findings, source, { callId });
    if (receipt?.replayed !== true) window.noteReview(receipt);
    // A deferred relay adopts the record (and so the successor tenure) after the
    // port returned, so the review receipt built from the port's answer still
    // names the outgoing tenure. Measured on 18.14.2: `recordPublished: true`
    // next to `tenure: 0` told the model the relay was incomplete and it kept
    // working. The receipt must describe the state that exists when it is read.
    if (receipt?.rejection === "RELAY_DEFERRED" && typeof receipt.handedOver?.tenure === "number") {
      receipt.tenure = receipt.handedOver.tenure;
      if (typeof receipt.handedOver.milestone === "string") receipt.milestone = receipt.handedOver.milestone;
    }
    if (receipt?.replayed === true) return receipt;
    this.#state.saveLedger();
    // Only the transition that shut the window reaches the adapter: the face is
    // already gone, so there is nothing to narrow twice.
    if (before === true && window.open === false && this.#onReviewAccepted !== null) {
      this.#onReviewAccepted(receipt);
    }
    return receipt;
  }

  // Relay (root §2.4 第 3 步). Retirement, the context barrier and the successor
  // start land as ONE durable record: a `custom` entry appended to this
  // session's own transcript through `appendEntriesAtomically`, which publishes
  // the whole journal with one rename (`relay-record.mjs` has the crash table).
  //
  // The commit is read from the session at CALL time, never captured: the
  // transcript a session writes to changes when it switches, and a captured
  // writer would append the record to the transcript the successor just left.
  handOverPort() {
    return handOverPort({
      present: this.#control,
      // `fenceThrough` counts the messages the incumbent has already seen, so
      // an operator message arriving after the relay is what the successor
      // answers (§2.4 第 4 步).
      fenceThrough: () => {
        const entries = this.#sessionManagerEntries();
        return entries === null ? 0 : entries.filter((entry) => entry?.type === "message").length;
      },
      commit: (payload) => this.commitRelayRecord(payload),
      // A relay asked for while this session's own turn is live (the normal
      // case: a tool call always runs with its prompt in flight). The intent is
      // published durably, and `performDeferredRelay` runs it at `agent_end`.
      defer: (intent) => this.deferRelay(intent),
    });
  }

  // Record the deferred intent. Nothing about the tenure changes here.
  //
  // The findings travel with it so the later commit can check that the
  // obligation still stands: a successor must never be opened because of a
  // ledger state that has since been re-filled by another review.
  async deferRelay({ findings = null, record = null } = {}) {
    if (this.#state === null) return { ok: false, detail: "this session has no state" };
    return await this.#state.noteRelayPending({
      findings,
      // The record prepared in this turn rides inside the intent: the idle
      // boundary cannot publish anything new on this host.
      record,
      commitPending: (payload) => this.commitCustomEntry(RELAY_PENDING_TYPE, payload),
    });
  }

  // Perform a relay that was deferred. Called at the host's idle boundary
  // (`agent_end` with no continuation) and, at startup, for an intent a previous
  // process recorded.
  //
  // The attempt is the SAME commit-then-reset the synchronous path uses; the
  // session is idle now, so `present.reason()` no longer reports streaming and
  // the switch can run. A session that turned busy again answers `RELAY_BUSY`
  // and the intent stays on disk for the next boundary.
  async performDeferredRelay({ maxPauseMs = 1000 } = {}) {
    const pending = this.#state?.relayPending ?? null;
    if (pending === null) return null;
    // `agent_end` is emitted INSIDE the host's own in-flight bracket
    // (`#endInFlight` → `#flushPendingAgentEnd` → `emit`), so a commit attempted
    // immediately still reads `SESSION_STREAMING` — the host's honest answer
    // about a state that clears only after this handler returns. The adapter
    // therefore waits for its own bracket to clear before attempting anything.
    // If it does not clear inside the bounded yield, nothing has changed and the
    // intent stays for the next idle boundary.
    const released = await this.#control.waitOutsideOwnTurn({ maxPauseMs });
    if (released.ok !== true) {
      return {
        ok: false,
        reason: released.reason,
        busy: released.busy ?? null,
        deferred: true,
        detail:
          "the host's agent_end in-flight bracket did not clear inside the adapter's bounded yield; " +
          "the pending relay intent stays on disk for the next idle boundary",
      };
    }
    // The record was COMMITTED in the live turn and carried by the intent. The
    // port is told about it explicitly, and told how to CHECK it: a carried
    // record is used only when the journal really holds the entry it came from,
    // so a prepared-but-unpublished draft is never mistaken for a commit.
    //
    // Nothing is rebuilt here. This boundary is past the host's writable life
    // (measured on 18.14.2: a write attempted here lands nowhere), so a record
    // rebuilt here would describe a moment nobody could record.
    const carried = preparedRecord(pending);
    // `defer` is deliberately absent: this IS the deferred step, so deferring
    // again would re-record an intent that already exists.
    const direct = handOverPort({
      present: this.#control,
      fenceThrough: () => {
        const entries = this.#sessionManagerEntries();
        return entries === null ? 0 : entries.filter((entry) => entry?.type === "message").length;
      },
      commit: (payload) => this.commitRelayRecord(payload),
      // The record the turn published, and the only evidence that may make the
      // boundary skip a second publish: the journal's own entry.
      carried,
      carriedDurable: () => this.#carriedRecordEntryId(carried),
      // The successor's copy when the host cannot take it. Root §1.3 licenses
      // this file for context-boundary facts; the host's publish still happened
      // first, so this is a second durable view, not a substitute.
      retain: (payload) => this.retainRelayRecord(payload),
    });
    const done = await direct.handOver({ retire: true, openSuccessor: true, barrier: true });
    if (done?.ok !== true) {
      // A transient refusal (the session went busy again) keeps the intent: the
      // next idle boundary retries.
      //
      // A PERMANENT refusal is a defect of this adapter's own wiring — the
      // context is stale, or the host lost its atomic publish — and must be
      // loud. Dropping the intent silently is how a real relay once became a
      // no-op that looked like success: the receipt had said "deferred", and
      // nothing ever contradicted it. The intent is kept AND the failure is
      // reported, so the next boundary retries and the operator sees why.
      if (done?.reason === "NO_SESSION_CONTROL" || done?.reason === "NO_ATOMIC_RECORD") {
        const failure = {
          ok: false,
          reason: "RELAY_DEFERRED_UNREACHABLE",
          cause: done.reason,
          detail:
            "a deferred relay could not be performed: " +
            (done.detail ?? "the host surface is not reachable") +
            ". The intent is kept, so the next idle boundary retries.",
          milestone: this.#state.ledger.milestone,
          tenure: this.#state.ledger.tenure,
        };
        this.#state.noteRelayFailure?.(failure);
        return failure;
      }
      return done;
    }
    // The ledger side of the step. The synchronous path opens the successor in
    // `ReviewMember` (compose), but a deferred relay has no member in scope: the
    // review that asked for it returned long ago. Without this the record and
    // the transcript would say "successor" while the ledger still named the
    // retired tenure — the half-tenure the spec forbids, in the opposite
    // direction.
    //
    // The ledger half is ONE step: adopt the record. `adoptRelayRecord` installs
    // the fence AND sets the ledger to the tenure and milestone the record
    // names (`ReviewLedger.adoptSuccessor`), with the successor flag set and an
    // empty ledger — which is exactly what `openSuccessor` would produce. Calling
    // `openSuccessor` as well would advance the tenure a SECOND time for one
    // relay, which is how a deferred relay once reached tenure 2.
    //
    // It is also the crash-recovery step: a restart adopts the same record the
    // same way, so "performed now" and "performed before a crash" converge on
    // one state.
    // The ledger belongs to the SUCCESSOR now, and so does its file. Without
    // this, `saveLedger` wrote the successor's tenure and fence into the
    // incumbent's `.session.json`, and `.Herdchestra/<successor>.session.json`
    // was never created at all (measured live on 18.14.2).
    const successorId = done.record?.successorSessionId ?? null;
    if (typeof successorId === "string" && successorId.length > 0) this.#state.adoptSessionId(successorId);
    // The ledger says the record exists; the barrier is armed next, because it
    // governs the SUCCESSOR's requests and those begin at the switch.
    this.#state.adoptLedgerRecord(done.record);
    // Arm BEFORE saving. `saveLedger` writes the fence alongside the tenure, so
    // saving first persisted a successor ledger with no barrier: a restart then
    // rebuilt a successor that hides nothing. The synchronous path already saved
    // after arming; this path must end in the same file.
    this.#state.armFence(done.record);
    this.#state.saveLedger();
    // Only a relay that really landed clears an intent, and it clears the one it
    // discharged: an intent recorded after this attempt names a newer obligation.
    this.#state.clearRelayPending({ throughTenure: pending.tenure });
    return {
      ...done,
      ok: true,
      milestone: this.#state.ledger.milestone,
      tenure: this.#state.ledger.tenure,
    };
  }

  // The session manager of the session this surface serves, or null.
  #sessionManagerEntries() {
    const manager = this.#ctx?.sessionManager ?? this.#ctx;
    if (manager === null || manager === undefined || typeof manager.getEntries !== "function") return null;
    try {
      const entries = manager.getEntries();
      return Array.isArray(entries) ? entries : null;
    } catch {
      return null;
    }
  }

  // The atomic publish. `appendEntriesAtomically` stages the entry and replaces
  // the whole journal in one rename, so the record exists complete or not at
  // all; a failed publish rolls the staged entry back and throws.
  //
  // A host without that method answers `{ok: false}` — the relay port then
  // refuses with `NO_ATOMIC_RECORD` and performs none of the three parts, which
  // is the honest answer rather than three separate writes that a crash could
  // split.
  async commitRelayRecord(payload) {
    const written = await this.commitCustomEntry(RELAY_RECORD_TYPE, payload);
    // The record is on disk, so the state's own copy is stale no more.
    if (written.ok === true) {
      this.#state?.noteRelayCommitted();
      // Keep the adapter's own copy the moment the host's publish lands
      // (root §1.3). It is a SECOND view of the same fact, not a replacement:
      // the host's entry is the primary one. Writing it here means a crash
      // anywhere after this point still leaves the tenure rebuildable, even
      // though the host seals its manager before the turn ends.
      //
      // A failed mirror does not fail the commit — the host's publish already
      // succeeded — but it is reported in `mirror` so a caller can tell.
      written.mirror = this.#state?.writeRelayRecord?.(payload) ?? null;
    }
    return written;
  }

  // One atomic publish of one custom entry, through the host's journal
  // transaction. Both relay facts use it — the deferred intent and the record
  // itself — so there is exactly one place that knows how this host commits.
  //
  // ## The entry id is not evidence of a durable publish
  //
  // Measured on omp 18.14.2 (DevOps probe, `/tmp/hc-devops-final/harness`): the
  // host's atomic batch can DISCARD its staged temp file and then report
  // "Atomic session batch was superseded before commit", rolling the entry out
  // of the in-memory journal. `appendCustomEntry` has already returned an id by
  // then, so an adapter that trusted the id answered `{ok: true}` for a record
  // that exists nowhere — the successor's ledger advanced, and no
  // `herdchestra.relay` entry was written at all.
  //
  // So the publish is READ BACK: a commit is `ok` only when the entry this call
  // staged is still in the journal afterwards AND the host confirms it on disk.
  // The read-back is the journal's own view (`getEntries`), not a parse of the
  // JSONL here — the host owns that format.
  //
  // The read-back also tells apart two failures that the thrown error cannot:
  //
  //   - `recovered: true` — the entry survived in the journal while the publish
  //     was superseded. The host keeps it in memory and the next publish carries
  //     it, so a retry can still make the record durable, and a later start can
  //     still read it. Retrying is safe because the write is idempotent for one
  //     tenure: a second record for the same tenure describes the same fact;
  //   - `recovered: false` — the entry was rolled back. Nothing exists, and no
  //     retry of THIS call can help; the caller must report the failure.
  async commitCustomEntry(customType, payload) {
    const manager = this.#ctx?.sessionManager ?? this.#ctx;
    if (manager === null || manager === undefined || typeof manager.appendEntriesAtomically !== "function") {
      // `unavailable` distinguishes "this host has no such primitive" from
      // "the primitive refused": only the first is permanent.
      return { ok: false, unavailable: true, detail: "the host exposes no atomic entry publish" };
    }
    let id = null;
    try {
      await manager.appendEntriesAtomically(() => {
        id = manager.appendCustomEntry(customType, payload);
        return id;
      });
    } catch (error) {
      return {
        ok: false,
        detail: String(error?.message ?? error),
        // The entry is in the journal even though the publish was superseded,
        // so a retry can still land it (see above).
        recovered: this.#journalCarries(manager, customType, id),
      };
    }
    // A resolved publish is not yet proof either: the host can resolve after
    // discarding the staged temp (its commit guard returns, the caller throws
    // only in some paths). The journal is the fact this adapter can read.
    if (!this.#journalCarries(manager, customType, id)) {
      return { ok: false, detail: "the atomic publish resolved but the entry is not in the journal", recovered: false };
    }
    return { ok: true, id };
  }

  // Whether the journal still carries the entry this call staged. `id` is the
  // host's own entry id, so a match names THIS write and not an earlier one of
  // the same type (a previous tenure's record, or the pre-switch copy).
  //
  // ## The file answers when the journal cannot
  //
  // Measured on omp 18.14.2 at the terminal `agent_end` (final-relay-check,
  // instrumented): after the manager is sealed, `getEntries()` returns NO relay
  // entry although the transcript file plainly carries one. A read-back that
  // consults only the journal therefore reports a publish that DID land as a
  // failure — which is what turned a completed switch into
  // `RELAY_RECORD_NOT_DURABLE`.
  //
  // The id is the evidence in both views: the host mints it and writes it ON the
  // entry, so a line in the file with the same id is the same write. The file is
  // read only for the adapter's own `customType`, and only when the journal
  // could not answer; a file that cannot be read is still not evidence.
  #journalCarries(manager, customType, id) {
    if (typeof id !== "string" || id.length === 0) return false;
    if (typeof manager?.getEntries === "function") {
      try {
        const entries = manager.getEntries();
        if (Array.isArray(entries)) {
          const found = entries.some(
            (entry) => entry?.type === "custom" && entry?.customType === customType && entry?.id === id,
          );
          if (found) return true;
        }
      } catch {
        // A journal that cannot be read is not evidence that the write landed;
        // the file below is the remaining view.
      }
    }
    return this.#fileCarries(customType, id);
  }

  // Whether the transcript FILE carries an entry with this customType and id.
  // The durable view once the host has sealed the manager.
  #fileCarries(customType, id) {
    const manager = this.#ctx?.sessionManager ?? this.#ctx;
    const read = manager?.getSessionFile;
    if (typeof read !== "function") return false;
    let file = null;
    try {
      file = read.call(manager);
    } catch {
      return false;
    }
    if (typeof file !== "string" || file.length === 0) return false;
    let text = null;
    try {
      text = readFileSync(file, "utf8");
    } catch {
      return false;
    }
    for (const line of text.split("\n")) {
      if (line.trim() === "" || !line.includes(id)) continue;
      let entry = null;
      try {
        entry = JSON.parse(line);
      } catch {
        // A torn last line is a crash during append; it carries no entry.
        continue;
      }
      if (entry?.type === "custom" && entry?.customType === customType && entry?.id === id) return true;
    }
    return false;
  }

  // The journal entry id that holds a record an earlier turn carried, or null
  // when the journal does not hold it.
  //
  // This is the ONLY evidence that lets the idle boundary use a carried record
  // instead of publishing its own: a prepared payload is a draft, and a draft is
  // not a commit. The match is on the payload's own fields, because the intent
  // names the record it carries but not the record's entry id.
  #carriedRecordEntryId(record) {
    if (record === null || typeof record !== "object") return null;
    const manager = this.#ctx?.sessionManager ?? this.#ctx;
    // Measured on omp 18.14.2 (`/tmp/hc-devops-final`, patch-storm3): at the
    // idle boundary the manager is already sealed, and its in-memory journal no
    // longer reflects the file — `getEntries()` answered NO relay entry while
    // the transcript on disk carried `herdchestra.relay` with `published: true`.
    // Reading only the journal therefore declared a durable record undurable and
    // fell through to a fresh publish that cannot land, skipping the switch.
    //
    // So the journal is asked first (it is the cheap, in-process view) and the
    // TRANSCRIPT FILE second, which is the durable fact this decision is about.
    // A record found in the file is returned under its own entry id.
    const fromJournal = this.#recordEntryIn(manager, record);
    if (fromJournal !== null) return fromJournal;
    const fromFile = this.#recordEntryInFile(record);
    if (fromFile !== null) return fromFile;
    // The last view: the adapter's own record (root §1.3). At the sealed
    // boundary the host's journal is gone and no successor transcript exists,
    // so this is the only remaining evidence — and it is the SAME payload the
    // host published, written by this adapter's atomic replace.
    return this.#state?.relayRecords?.().some((entry) => this.#sameRecord(entry.data, record)) === true
      ? `adapter:${record.tenure}`
      : null;
  }

  // Whether two record payloads describe the same succession.
  #sameRecord(left, right) {
    if (left === null || typeof left !== "object" || right === null || typeof right !== "object") return false;
    return (
      left.tenure === right.tenure &&
      left.milestone === right.milestone &&
      left.notice === right.notice &&
      left.fenceThrough === right.fenceThrough &&
      left.successorSessionId === right.successorSessionId &&
      left.previousSessionFile === right.previousSessionFile
    );
  }

  // The entry id of `record` in the manager's in-memory journal, or null.
  #recordEntryIn(manager, record) {
    if (manager === null || manager === undefined || typeof manager.getEntries !== "function") return null;
    let entries;
    try {
      entries = manager.getEntries();
    } catch {
      return null;
    }
    if (!Array.isArray(entries)) return null;
    for (let index = entries.length - 1; index >= 0; index -= 1) {
      const entry = entries[index];
      if (entry === null || typeof entry !== "object") continue;
      if (entry.type !== "custom" || entry.customType !== RELAY_RECORD_TYPE) continue;
      const data = entry.data;
      if (data === null || typeof data !== "object") continue;
      if (
        data.tenure === record.tenure &&
        data.milestone === record.milestone &&
        data.notice === record.notice &&
        data.fenceThrough === record.fenceThrough &&
        data.successorSessionId === record.successorSessionId &&
        data.previousSessionFile === record.previousSessionFile
      ) {
        return typeof entry.id === "string" && entry.id.length > 0 ? entry.id : null;
      }
    }
    return null;
  }

  // The entry id of `record` in the transcript FILE, or null.
  //
  // The file is the durable fact, and it is the only view left once the host has
  // sealed the manager. It is read through the host's own `getSessionFile()` and
  // parsed line by line for the entries this adapter wrote itself
  // (`RELAY_RECORD_TYPE`); nothing else about the host's format is assumed.
  // A file that cannot be read is not evidence that the record is missing, so a
  // read failure answers null (the caller then refuses honestly rather than
  // treating an unreadable file as a commit).
  #recordEntryInFile(record) {
    const manager = this.#ctx?.sessionManager ?? this.#ctx;
    const read = manager?.getSessionFile;
    if (typeof read !== "function") return null;
    let file = null;
    try {
      file = read.call(manager);
    } catch {
      return null;
    }
    if (typeof file !== "string" || file.length === 0) return null;
    let text = null;
    try {
      text = readFileSync(file, "utf8");
    } catch {
      return null;
    }
    for (const line of text.split("\n")) {
      if (line.trim() === "" || !line.includes(RELAY_RECORD_TYPE)) continue;
      let entry = null;
      try {
        entry = JSON.parse(line);
      } catch {
        // A torn last line is a crash during append; it carries no entry.
        continue;
      }
      if (entry?.type !== "custom" || entry?.customType !== RELAY_RECORD_TYPE) continue;
      const data = entry.data;
      if (data === null || typeof data !== "object") continue;
      if (
        data.tenure === record.tenure &&
        data.milestone === record.milestone &&
        data.notice === record.notice &&
        data.fenceThrough === record.fenceThrough &&
        data.successorSessionId === record.successorSessionId &&
        data.previousSessionFile === record.previousSessionFile
      ) {
        return typeof entry.id === "string" && entry.id.length > 0 ? entry.id : null;
      }
    }
    return null;
  }

  // Keep a relay record in the adapter's own journal (root §1.3).
  //
  // The host's atomic publish is still the primary copy: this is written only
  // when the host cannot take the successor's copy (the manager is sealed by the
  // time the switch runs), and it carries the SAME payload so either view can
  // rebuild the fence. A disk that refuses it answers `{ok:false}` and the port
  // refuses honestly rather than claiming a tenure it cannot rebuild.
  retainRelayRecord(payload) {
    if (this.#state === null) return { ok: false, detail: "this session has no state" };
    return this.#state.writeRelayRecord(payload);
  }

  // `decision` carries what the review established (the findings that emptied
  // the ledger). It reaches the port so a deferred intent can record them; the
  // synchronous path ignores them.
  async handOver(decision = null) {
    const done = await this.#state.handOver(this.handOverPort(), decision);
    // A relay DEFERRED after its record landed is a succession whose transcript
    // switch is still pending. The record is the durable fact §5.3.2 asks for,
    // and the fence it carries already keeps the incumbent's conversation out
    // of every request, so the successor's ledger and fence are installed NOW.
    //
    // What this does NOT claim: the transcript was not replaced, and the
    // receipt says so (`retired: false`, `barrier: false`, `successor: null`).
    // Installing the fence is not reporting a switch — it is honouring the
    // record that already exists. Without it a crash before the idle boundary
    // would leave a durable record and a ledger that says tenure 0, which is
    // the half tenure in the other direction.
    if (done?.reason === "RELAY_DEFERRED" && done?.recordPublished === true) {
      const record = this.#state.relayPending?.record ?? null;
      if (record !== null && typeof record === "object") {
        // The LEDGER half only. The fence is armed at the transcript switch
        // (`performDeferredRelay` → `armFence`), because until then the live
        // requests belong to the tenure that published the record: arming it
        // here made the model lose its own conversation mid-turn (measured on
        // 18.14.2: 116 forks in one turn, 18 worker sessions).
        this.#state.adoptLedgerRecord(record);
        this.#state.saveLedger();
        // The receipt was built BEFORE this adoption, so it still reports the
        // outgoing tenure. Measured live (18.14.2): the model read
        // `recordPublished: true` next to `tenure: 0` and kept working for
        // 143 tool calls, never ending its turn. The state it is told must be
        // the state that exists when it reads it.
        done.tenure = this.#state.ledger.tenure;
        done.milestone = this.#state.ledger.milestone;
        done.fence = this.#state.fence === null ? null : { successor: this.#state.fence.successor };
        // `handedOver` is the port's receipt and is read on its own, so the
        // corrected facts are written there too: one receipt must not report two
        // different tenures depending on which half a caller reads.
        if (done.handedOver !== null && typeof done.handedOver === "object") {
          done.handedOver.tenure = this.#state.ledger.tenure;
          done.handedOver.milestone = this.#state.ledger.milestone;
        }
      }
    }
    return done;
  }

  // A Plan handoff starts an independent Manager session with the draft bytes
  // (spec §6.1).
  //
  // The session is started the same way `fork` starts a worker — through the
  // SDK factory the host injects — because the host gives an extension no tool
  // dispatcher (`loader.ts:454` passes only `api`), so the `task` route this
  // used before could never run on a real host. `runAgent` stays available for
  // a harness that injects a dispatcher; it is the fallback, not the path.
  //
  // The Manager's identity is `main`, not `sub`: spec §6 makes Plan the top
  // level and §7 puts the Manager beside it, and `roleOfAgent` reads the role
  // from `agent.kind`. A lane with `role: MANAGER` therefore narrows to the
  // `js-manager` face and to `fork`/`join`/`review` — never `js-worker`.
  async startManager(draft) {
    const name = `manager-${Date.now().toString(36)}`;
    // The draft is the Manager's ONLY starting material (spec §6.1): it is the
    // first turn's brief, not a note attached to one.
    const started = await this.#lanes.start({
      name,
      role: ROLE.MANAGER,
      brief: draft,
      depth: this.#laneDepth(),
      // The Manager judges from the work tree, not from this Plan's transcript
      // (§5.3 barrier): it is a separate session that never sees this one.
      register: true,
    });
    if (started.ok !== true) {
      // A lane that did not start is reported as it is. `NO_SESSION_FACTORY` is
      // the honest answer on a host without the SDK; there is no second route.
      return { ok: false, reason: started.reason, session: name };
    }
    this.#laneSessions.set(name, started.lane.session);
    // §6.1: the hand-off's answer is the Manager's first result, so this waits
    // for the turn instead of returning a session nobody can read. The wait is
    // on the lane's own end event, which is what `join` reads — not a timer and
    // not a `prompt()` that would resolve only after the whole turn anyway.
    const ended = await started.lane.ended;
    if (ended?.error !== undefined) {
      return { ok: false, reason: "AGENT_FAILED", session: name, detail: ended.error?.message ?? null };
    }
    // 根 §2.2: a Plan that started its Manager has reached the verified exit.
    // The hand-off does not move the ledger (the Plan stays at 待执行), so the
    // fact is recorded here, where it happened, and read by `turnExit`.
    this.#handedOff = true;
    // The wait is bounded by the Manager's TURN, never by its whole work: the
    // lane's end event is one turn (`agent_end` with `isTerminal !== false`), so
    // a Manager that keeps working continues in later turns of the same
    // session. §6.1's requirement is that the hand-off has an answer — the
    // Manager's own first result — and not a session object nobody can read;
    // it does not ask Plan to wait for the Manager to finish the task. The
    // session stays in the roster, so its later turns are still reachable and
    // its own `fork`/`join` keep working.
    return {
      ok: true,
      session: name,
      // The Manager's answer is its report, taken the same way `join` takes a
      // worker's: the lane's own assistant text, never a summary.
      report: ended?.text ?? "",
      agentId: started.lane.agentId ?? null,
    };
  }

  // The first incumbent's question (根 §2.4 第 1 步, spec §5.2). It is the same
  // `ask` a program makes: the question is recorded, the receipt carries
  // `interrupt: true`, and the turn is interrupted so the operator can reply.
  // The reply arrives as the operator's next message; nothing here blocks on a
  // dialog the host may not have.
  async askOperator(question) {
    const asked = await askPort({ ui: this.#binding.ui, questions: this.#questions }).ask(question);
    // 根 §2.4 第 1 步: the first empty review is not a review — it is a question,
    // and the operator must get the turn back to answer it. The ledger has
    // already been left where it was; this only ends the turn.
    //
    // The fact is recorded BEFORE the abort: `turnExit` reads it when the
    // aborted turn ends, and an ask whose abort is reported as an operator
    // interrupt must still be a legal stop rather than a nudge.
    this.#state?.markAskedInS1?.(question);
    await this.#control.interruptTurn({ reason: `herdchestra: first review asks (${question})` });
    // The caller reads the operator's words when the host's dialog answered
    // in-call, and the whole receipt otherwise: a question that was asked and
    // not yet answered has no answer to report, and inventing one would tell
    // the first review that the operator had spoken.
    return typeof asked.answer === "string" ? asked.answer : asked;
  }

  // The `ask` member's host side (spec §3.3 example 7). The surface already
  // parked the program; this records the question and interrupts the turn, so
  // control returns to the operator. A later program of this session resumes
  // the parked body with `this.unpark(name, answer)`.
  async ask(question, { program = null } = {}) {
    const asked = await askPort({ ui: this.#binding.ui, questions: this.#questions }).ask(question, { program });
    // A legal stop is a FACT, not a guess about text: a Plan that asked in S1
    // is waiting for the operator, and the unattended stop rule reads this
    // (`nudge.mjs`). A Manager never asks, so the marker cannot mask its work.
    this.#state.markAskedInS1(question);
    // The park already happened; ending the turn is the other half of `ask`.
    // An abort the host refuses does not undo the park: the receipt still says
    // `interrupt: true`, and the program is resumable either way.
    await this.#control.interruptTurn({ reason: `herdchestra: ask (${question})` });
    return asked;
  }

  // Scope admission for `tool_call` (spec §6.2). The annotation travels in
  // the tool input as `scope`; the adapter passes it through unchanged.
  // Every tool call goes through Scope (spec §6.2). The only exemptions are
  // the ones §6.2 names, and Scope itself decides them: this adapter does not
  // exempt a tool by class. A host tool the adapter does not own cannot be given
  // a `scope` schema here, so a call to it without an annotation is refused —
  // that is the rule, and the adapter reports the refusal instead of hiding it.
  scopeAdmit(event) {
    return this.#state.scopeHooks().admit({
      sessionId: this.#state.sessionId,
      member: event?.toolName,
      annotation: annotationOf(event),
    });
  }

  // Scope settle for `tool_result` (spec §6.2), after the call ran. The
  // booking is per model turn, so the turn number comes from the session.
  async scopeSettle(event) {
    return this.#state.scopeHooks().settle({
      sessionId: this.#state.sessionId,
      member: event?.toolName,
      annotation: annotationOf(event),
      // The host's turn id, so concurrent calls of one model turn share it.
      turnId: this.#turnId ?? this.#turns,
      callId: event?.toolCallId ?? null,
      observed: null,
    });
  }

  // Scope turn bookkeeping at the host's MODEL turn boundary. `turn_start`
  // fixes this turn's id, so every tool call of the turn books against one
  // turn even when several run concurrently; `turn_end` is the only place a
  // `meToo`-only turn is refused (spec §6.2).
  scopeOpenTurn(event) {
    const turnId = typeof event?.turnIndex === "number" ? `turn-${event.turnIndex}` : `turn-${this.#turns}`;
    this.#turnId = turnId;
    return { ok: true, turnId };
  }

  // Scope turn bookkeeping at the host's MODEL turn boundary (spec §6.2).
  //
  // `closeTurn` answers a Promise: the judgement needs a settled book, so a call
  // arriving while a settle is in flight is queued behind it. Awaiting it here
  // keeps this method's contract `refusal | null` — without the await a
  // no-refusal turn returns the Promise (truthy) instead of `null`, and a caller
  // that tests truthiness reports a refusal that never happened.
  async scopeCloseTurn() {
    const refusal = await this.#state.scopeHooks().closeTurn({ sessionId: this.#state.sessionId });
    if (refusal === null || refusal === undefined) return null;
    return refusal;
  }

  // Scope projection for `context` (spec §6.2).
  scopeProject({ items }) {
    return this.#state.scopeHooks().project({ sessionId: this.#state.sessionId, items });
  }

  // The synchronous snapshot of the two exempt members (§6.2). The answer is a
  // value, never a promise: `ScopeHooks#ask` reads the newest committed facts
  // and refuses (`SCOPE_SNAPSHOT_BUSY`) while a call is still settling, so the
  // model asks again rather than reading a budget that is about to change.
  scopeSnapshot(member) {
    return this.#state.scopeHooks().ask(member, this.#state.sessionId);
  }

  // Pair Hint projection for `context` (spec §6.1). The hint's handle list is
  // the session's unsettled park handles, and a changed list opens the epoch
  // that may show it; both facts live in the session state, which is also what
  // the tests drive without a surface.
  async pairHintProject(messages, options = {}) {
    return await this.#state.pairHintProject(messages, options);
  }

  // The request gate (§8.1 a): whether the last request was actually projected.
  // The `tool_call` hook refuses every call while it says no.
  requestRefusal() {
    return this.#state.requestRefusal();
  }

  // The id of the request in flight, or null. The request path records its
  // verdict against it (`session-state.mjs` `openContextRequest`).
  requestInFlight() {
    return this.#state.requestInFlight;
  }

  // Lanes of a fission group, for tests and for the abort path.
  lane(laneId) {
    return this.#fissionLanes.get(laneId) ?? null;
  }
}

export { SURFACE_MEMBERS, ROLES };
