// What one plugin activation owns (AGENTS.md §7.1).
//
// A host location can load the plugin again after a config change; the old
// activation's registrations are disposed by the host. Everything stateful the
// adapter owns hangs off this object, so a reload starts clean instead of
// leaking the previous world:
//
//   states      one `SessionState` per host session (programs, ledger, Scope,
//               language, park handles) — session-state.mjs
//   workers     one worker table per Manager session — worker-sessions.mjs
//   fissions    one group table per worker session — compose/fission/fission.mjs
//   lanes       lane sessions by lane id, across every group
//   asks        pending `ask` questions per session — ask-channel.mjs
//   relayHost   the three `review` side effects — relay-host.mjs
//   views       command views, shared by every program — runtime-adapters.mjs
//
// Nothing here touches the host: every host call goes through a port this
// object holds, which keeps the whole adapter testable without OpenCode.

import { makeViews } from "./runtime-adapters.mjs";
import { registerIdentity, readIdentityOf } from "./identity.mjs";
import { SessionStates } from "./session-state.mjs";
import { AskChannel } from "./ask-channel.mjs";
import { makeRelayHost } from "./relay-host.mjs";
import { Fission } from "../../compose/fission/fission.mjs";
import { WorkerTable } from "./worker-sessions.mjs";
import { makeCommandPort } from "./command-port.mjs";
import { openPairHint } from "./pair-hint-request.mjs";
import {
  makeForkJoinPorts,
  makeRelayPorts,
  makeControlPort,
  makeFissionPorts,
  makeDevopsPort,
  makeDraftPort,
  planDraftPath,
} from "./program-ports.mjs";

// A session id made safe for a file name. The ledger file and Plan's draft must
// name one session the same way, so this is the only sanitiser on this side.
const safeName = (value) =>
  String(value).replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";

// The Plan relay that opened this session, when there is one. A Plan's
// successor reads and writes the draft its predecessor wrote (§2.2): the record
// carries the file's name and the workspace it lives in, and this is the reader
// both the draft port and the announced path use.
const planRelayOf = (activation, sessionID) => {
  const state = activation?.states?.get?.(sessionID) ?? null;
  const record = state?.relay?.record?.() ?? null;
  if (record === null || record.corrupt === true) return null;
  if (record.kind !== "plan" || record.successor !== sessionID) return null;
  return record;
};

// The workspace a session's draft lives in. The Plan's own session root is the
// announced one (`draftPath`), and a relayed Plan successor inherits the
// predecessor's, because it continues the predecessor's file.
export const draftRootOf = (activation, sessionID) => {
  const relay = planRelayOf(activation, sessionID);
  if (relay !== null && typeof relay.draftRoot === "string" && relay.draftRoot.length > 0) return relay.draftRoot;
  return stateOf(activation, sessionID).root;
};

// The draft file's name: the session's own, or the predecessor's when this
// session is a Plan's successor.
export const draftNameOf = (activation, sessionID) => {
  const relay = planRelayOf(activation, sessionID);
  if (relay !== null && typeof relay.draft === "string" && relay.draft.length > 0) return relay.draft;
  return safeName(sessionID);
};

// The road-bound devops of one session (根 §2.3). Keyed by the session that owns
// the road, so two Managers cannot see or release each other's devops.
export const devopsRoadOf = (activation, sessionID) => {
  const key = typeof sessionID === "string" && sessionID.length > 0 ? sessionID : "";
  let road = activation.devopsInFlight.get(key);
  if (road === undefined) {
    road = new Set();
    activation.devopsInFlight.set(key, road);
  }
  return road;
};

// How many lanes one group may have. The host documents no cap on child
// sessions, so the adapter's own limit is the only one: a larger group is
// refused as `CapacityExceeded` rather than started and abandoned.
export const FISSION_CAPACITY = 32;

// `hooks` carries what needs the calling session or a live tool call:
// `forkWorker` and `joinWorker` are supplied by the plugin because a port call
// does not carry the caller's session id.
export const makeActivation = ({ commandPort, sessions = null, root = null, hooks = {} } = {}) => {
  const activation = {
    sessions,
    root,
    commandPort: commandPort ?? makeCommandPort({ cwd: root }),
    views: makeViews(),
    states: new SessionStates({
      root,
      rootOf: hooks.rootOfSession ?? null,
      // §2.3 closing reads this at the moment of the review: a Manager whose
      // roster still holds a running worker has unsettled resources, so its
      // empty review at `close` does not close the work. Read through
      // `workerTableOf` so a successor's adopted roster counts too — a
      // predecessor's worker is still a running process.
      resourcesSettled: (sessionID) => {
        const table = workerTableOf(activation, sessionID);
        return table.liveCount() === 0;
      },
    }),
    asks: new AskChannel({ sessions }),
    // Supplied by the plugin.
    forkWorker: hooks.forkWorker ?? (async () => null),
    joinWorker: hooks.joinWorker ?? (async () => null),
  };
  // The relay host writes the one record the two gates read (根 §2.4 第 3 步). It needs
  // the workspace root for that file, and the session table so the successor's
  // ledger is recorded where its own state will read it. See relay-host.mjs.
  activation.relayHost = makeRelayHost({
    sessions,
    root,
    // The relay reads the predecessor's original input from that session's own
    // `.Herdchestra` (its workspace), not from the plugin host's root.
    rootOfSession: (sessionID) => stateOf(activation, sessionID).root,
    stateOf: (sessionID) => stateOf(activation, sessionID),
    // Every session the relay creates is registered before its first prompt, so
    // the successor's brief and a Plan's draft are never captured as the
    // operator's original input (`identity.mjs`).
    registerCreated: (sessionID, fact) => activation.registerIdentity(sessionID, fact),
    // The predecessor's workers are live host sessions, not part of the
    // transcript. A relay that did not name them would leave them running with
    // nobody holding their names (根 §2.4 第 3 步), so the record captures them.
    workersOf: (sessionID) => {
      const table = activation.workers.get(sessionID);
      if (table === undefined) return { roster: [], unclaimed: [], devops: [] };
      return {
        // Every worker the predecessor holds, live or idle: 根 §2.4 keeps process
        // ownership and the road-bound `devops` across the relay.
        roster: table.rosterEntries(),
        // Results that finished but were never joined. Dropping them here would
        // make the successor redo work to learn what the predecessor knew.
        unclaimed: table.unclaimed(),
        // The devops bound to THIS session's road, whether or not it is busy
        // right now. The road is keyed by the session that owns it, so a
        // successor is never handed a devops another Manager started.
        devops: [...devopsRoadOf(activation, sessionID)].filter((name) => table.get(name) !== null),
      };
    },
    // A Plan's saved draft (§2.2) travels with a Plan relay: the successor must
    // write and read the file its predecessor wrote, not a new one keyed by its
    // own session id.
    draftOf: (sessionID) => ({ root: draftRootOf(activation, sessionID), name: draftNameOf(activation, sessionID) }),
  });
  activation.workers = new Map();
  activation.fissions = new Map();
  activation.lanes = new Map();
  // Worker sessions that a `fork` started, and the role each keeps (§4.2). The
  // KEY is the child's session id: `fission` runs in the worker's own session,
  // and the session is the only identity a fork and a later call share (a
  // program is named by its tool-call id, which is never a session id).
  activation.fissionOrigins = new Map();
  // The road-bound devops of each session (根 §2.3: one devops per road). The
  // key is the session that owns the road: two Managers may each run their own
  // `devops`, and one Manager's fork must never release the other's.
  activation.devopsInFlight = new Map();
  activation.devopsRuns = new Map();
  // Sessions the operator stopped (root §1.1 item 2): the top-level Plan or
  // Manager and every descendant reached through workers, relay rosters and
  // fission lanes. A cancelled session may not fork or steer again.
  activation.cancelled = new Set();
  // Sessions interrupted because a fission group replaced their present. That
  // interrupt is deliberate and must not be read as the operator stopping the
  // work (`cancel.mjs`).
  activation.presentReplacements = new Set();
  // The parent of each session, as `session.created` reported it: `null` for a
  // top-level session, a session id for a child. The operator's original input
  // is captured only for a session this table proves is top-level
  // (original-input.mjs): a child's first prompt is a brief, not the operator's
  // words. A session missing from the table has an unknown parent and captures
  // nothing.
  activation.parents = new Map();
  // Sessions this adapter created, and the parent each was created with. The
  // fact is written BEFORE the session's first prompt, because the prompt hook
  // must know whether the receipt is the operator's or the adapter's own
  // delivery: a `session.created` event for a session the adapter just made
  // arrives after its first prompt, and a child created here has no parent fact
  // until then (identity.mjs).
  activation.identities = new Map();
  // When this activation started, and which sessions it has seen since. A
  // prompt from a session it has never seen belongs to a session that was
  // already running before the plugin loaded: that session predates the
  // original-input fact and must not capture a later prompt as its first
  // (original-input.mjs).
  activation.startedAt = Date.now();
  activation.observedSessions = new Set();
  // The agent each session runs as, as its events and requests reported it. The
  // turn-exit controller reads the identity here: a completion event names the
  // session, not the agent that ended it (turn-nudge.mjs).
  activation.agents = new Map();
  // The rounds already booked by the turn-exit controller, per session. One
  // round is one assistant message, and the host emits several completion
  // events for it; the table is what keeps one stop from becoming several
  // nudges.
  activation.nudgedRounds = new Map();
  // The identity each worker session runs as. It is NOT a constant: a lane is a
  // parallel present of its owner (§4.2), so a devops that fissions starts
  // devops lanes. The role is a property of the SESSION (its agent), so it is
  // keyed by the session and never by "the call in flight": two concurrent tool
  // calls of two sessions cannot clobber each other's identity.
  activation.laneRoles = new Map();
  activation.laneRoleOf = (owner = null) => laneRoleOf(activation, owner);

  const fission = makeFissionPorts(activation);
  // Plan's saved draft (§2.2), named per calling session so a hand-off reads
  // the file that session's Plan wrote.
  const draftFor = (sessionID) =>
    makeDraftPort({ root: draftRootOf(activation, sessionID), name: draftNameOf(activation, sessionID) });
  // The draft's path for one session, without opening a port: the Plan must be
  // told the exact path (§2.2), and its receipt is where this host can say it.
  // It uses the session's own workspace, the same one the port below writes to.
  activation.draftPath = (sessionID) =>
    planDraftPath(draftRootOf(activation, sessionID), draftNameOf(activation, sessionID));
  // `draft`, `relay` and `devops` are per call (`portsFor` below), not static
  // ports: a relay needs the calling session and its physical call id, a devops
  // needs the session whose road it runs on, and Plan's saved draft is named
  // after the session that wrote it (§2.2).
  activation.staticPorts = { fission };

  activation.fissionTableOf = (owner) => fissionTableOf(activation, owner);
  // Record one adapter-created session before it is prompted. Idempotent, and a
  // registration never overrides a parent the host already reported.
  activation.registerIdentity = (sessionID, fact = {}) => {
    const written = registerIdentity(root, sessionID, fact);
    if (written.ok === true) {
      activation.identities.set(sessionID, written.record);
      // The parent table and the observed set are the same fact in the shape the
      // prompt hook already reads: registering here means the prompt that
      // follows is not treated as a stranger's.
      activation.parents.set(sessionID, written.record.parentID);
      activation.observedSessions.add(sessionID);
    }
    return written;
  };
  activation.laneList = (groupId) =>
    [...activation.lanes.values()].filter((lane) => lane.groupId === groupId);

  // The ports one call reads. `state` is the calling session's state; the
  // worker ports go through the plugin's actions because they need the session.
  activation.portsFor = ({ state, sessionID, signal, call = null }) => {
    const worker = makeForkJoinPorts(activation, sessionID);
    const command = activation.commandPort;
    // The calling session and its physical call id, stated per call. A port call
    // does not carry them, and a mutable slot shared by the activation let one
    // interleaved tool call read another's context.
    const scope = call === null ? { sessionID, id: null, agent: null } : { sessionID, id: call.id ?? null, agent: call.agent ?? null };
    return {
      command:
        signal === undefined
          ? command
          : Object.freeze({
              run: (value, options = {}) => command.run(value, { ...options, signal }),
            }),
      control: makeControlPort(activation, sessionID),
      worker,
      fork: { fork: worker.fork, join: worker.join },
      join: { join: worker.join },
      relay: makeRelayPorts(activation, scope),
      // compose's `fission` port is the caller's own: `fission(owner, prompts)`
      // plus the five host steps that build the lanes.
      fission: fission.fissionFor(sessionID),
      draft: draftFor(sessionID),
      devops: makeDevopsPort(activation, scope),
      // The two exempt snapshot queries (§6.2). `ScopeHooks#ask` is synchronous
      // by contract and answers from the newest committed facts; a host that
      // answered a promise here would turn a snapshot into a park.
      scope: { ask: (member) => state.scopeOf().ask(member, sessionID) },
      state,
    };
  };

  return activation;
};

// The state of one session.
export const stateOf = (activation, sessionID) => activation.states.get(sessionID);

// The worker table of one Manager session (§4.1). Fork, join and the Manager's
// program members all read this one table, so the two spellings cannot drift.
//
// The table starts from the session's journal, never from an empty roster. A
// worker is a host session that outlives the plugin that forked it, and
// OpenCode reloads a plugin whenever a watched file changes; a roster kept only
// in memory made the reloaded activation answer `join` with "nothing to
// collect" while the child sessions were still running (measured 2026-11-01,
// `ses_e6ab649c8ffea0B023Dy3Aj2P0`). Every fact `join` decides on is written
// back to that same journal, and the first `join` of a restored table asks the
// host what each restored worker is doing now.
export const workerTableOf = (activation, sessionID) => {
  const existing = activation.workers.get(sessionID);
  if (existing !== undefined) return existing;
  const state = activation.states.get(sessionID);
  const table = new WorkerTable({
    sessions: activation.sessions,
    // A `fork`ed child is registered before its brief, so its first prompt is
    // never mistaken for the operator's original input (`identity.mjs`).
    onCreated: (sessionID, fact) => activation.registerIdentity(sessionID, fact),
    // The roster reaches the journal before it reaches a caller: a reload
    // between a fork and its first `join` must still know the worker exists.
    onRecord: (entry) => state?.recordWorker?.(entry),
    restored: state?.workerRoster ?? null,
  });
  activation.workers.set(sessionID, table);
  // The road-bound `devops` is a fact of the roster, not of the process: a
  // reloaded activation that forgot it would let this session fork a second
  // devops while the first one is still running (根 §2.4: the road-bound devops
  // does not change hands). Only a devops the roster still lists as running
  // counts, and "running" is the host's answer, not the journal's: a worker that
  // finished while no plugin was loaded is still `running` in the journal.
  // Binding is additive: a devops the journal lists holds the road. Releasing
  // it is a different question — it needs the host's answer, which is why only
  // the reconciled pass below releases, and only for a worker the host reports
  // finished (`reconciled: true`).
  const road = devopsRoadOf(activation, sessionID);
  const bindDevops = ({ reconciled = false } = {}) => {
    for (const worker of table.rosterEntries()) {
      if (worker.role !== "devops") continue;
      if (worker.state !== "idle") road.add(worker.name);
      else if (reconciled && table.get(worker.name)?.hostFinished === true) {
        // The host says this worker is not running, so it has released the road.
        road.delete(worker.name);
      }
    }
  };
  bindDevops();
  // The host is the authority on "still running", and asking costs one call.
  // Until the answer arrives the journal's word stands, which is why the bind
  // above runs first. The promise is kept on the table so a caller that needs
  // the reconciled answer can wait for it instead of guessing when it landed.
  table.reconciled = table.reconcile().then(() => bindDevops({ reconciled: true }), () => {});
  // A successor takes over what the relay record preserved (根 §2.4): its roster,
  // the results nobody joined and the road-bound `devops`. Registration only —
  // no brief is re-sent, no lane is reset. A record that were written but never
  // read would let this session fork a second worker under a name the
  // predecessor still holds.
  if (state !== undefined) {
    state.adoptRoster?.(table, road);
  }
  return table;
};

// The identity a lane runs as (§4.2): the owner's own role. A caller that names
// no owner role (a lane built outside a worker call, or a role this adapter does
// not fork) keeps `coder`, the only identity a lane could otherwise be.
export const laneRoleOf = (activation, owner = null) => {
  const named = activation.laneRoles?.get(owner) ?? activation.fissionOrigins?.get(owner)?.role ?? null;
  return named === "devops" ? "devops" : "coder";
};

// The fission groups of one worker session (§4.2). Admission, whole-group
// rollback, convergence and merge are compose's `Fission`; this function only
// supplies the host port and the two questions only the adapter can answer.
// Only a program that a `fork` started may fission; `origins` answers that from
// the table the plugin fills when it starts a child. The question is asked with
// the SESSION id: that is what the plugin keys the table by, and what `fission`
// resolves through the port's `ownerName()`.
export const fissionTableOf = (activation, sessionID) => {
  const existing = activation.fissions.get(sessionID);
  if (existing !== undefined) return existing;
  const groups = new Fission({
    port: activation.staticPorts.fission.forOwner(sessionID),
    origins: (owner) => activation.fissionOrigins?.has(owner) === true,
    capacity: (count) => count >= 2 && count <= FISSION_CAPACITY,
  });
  activation.fissions.set(sessionID, groups);
  return groups;
};

export const disposeActivation = (activation) => {
  activation.views.clear();
  activation.states.clear();
  activation.workers.clear();
  activation.fissions.clear();
  activation.lanes.clear();
  activation.devopsInFlight.clear();
  activation.devopsRuns.clear();
  activation.cancelled.clear();
  activation.presentReplacements.clear();
  activation.fissionOrigins.clear();
  activation.identities.clear();
  activation.laneRoles.clear();
  activation.parents.clear();
  activation.observedSessions.clear();
  activation.agents.clear();
  activation.nudgedRounds.clear();
};

export { openPairHint };
