// The OpenCode v2 plugin (AGENTS.md §7.1).
//
// `setup(ctx)` registers everything the host offers and nothing else:
//
//   ctx.agent.transform   four agents; every other agent hidden
//   ctx.tool.transform    js-manager, js-worker, js-review, plan, fork, join
//   ctx.session.hook      "context"/"compaction"/"generate" (request copy),
//                         "prompt" (operator message)
//   ctx.tool.hook         "execute.before" (admit), "execute.after" (settle)
//   ctx.event.subscribe   session lifecycle (language, worker completion)
//
// The returned cleanup disposes the activation, so a reload starts clean.
//
// Every stateful fact hangs off the activation (`activation.mjs`): one
// `SessionState` per host session, one worker table per Manager session, one
// fission table per worker session, the ask channel, the relay host and the
// command views. A port call carries no session id, so the program executor
// states the calling session and its physical call id per call
// (`activation.portsFor`); nothing is kept in an activation-wide slot that a
// concurrent tool call could overwrite. `fork`/`join` need the plugin's own
// action for the same reason.
//
// What the setup does NOT do is stated in `gaps.mjs`. A member that depends on a
// fact the host does not offer refuses with the gap it waits on, instead of
// pretending.

import path from "node:path";

import { definePlugin } from "./plugin-definition.mjs";
import { AGENT_DEFINITIONS, OTHER_AGENTS_HIDDEN } from "./agent-definitions.mjs";
import { STATIC_REMOVED_TOOLS, TOOL_NAMES, hostFacts } from "./capabilities.mjs";
import { registerTools } from "./tool-registrations.mjs";
import { makeActivation, disposeActivation, stateOf, workerTableOf, devopsRoadOf, laneRoleOf } from "./activation.mjs";
import { makeProgramExecutor, makeResumer } from "./program-tool.mjs";
import { forkReceipt, joinReceipt } from "./steer-receipt.mjs";
import { settledOutcomeOf } from "./worker-sessions.mjs";
import { createInput, devopsRefusal } from "./session-creation.mjs";
import { resolveChildModel } from "./model-inherit.mjs";
import { roleOf, workerRole } from "./agent-role.mjs";
import { ownsTool } from "./role-tools.mjs";
import { forkRequest } from "../../compose/program/role-brief.mjs";
import { refusal, REFUSALS } from "./refusals.mjs";
import { beforeCall, afterCall } from "./execute-hooks.mjs";
import { applyContextHook, ContextRefusal } from "./context-hook.mjs";
import { answerPendingAsk, applyPromptHook } from "./prompt-hook.mjs";
import { openGaps } from "./gaps.mjs";
import { openPairHint } from "./pair-hint-request.mjs";
import { inheritedBinding } from "./session-state.mjs";
import { handleTurnEnd } from "./turn-nudge.mjs";
import { propagateCancellation } from "./cancel.mjs";

// The event stream, as something `for await` really iterates.
//
// The host's `event.subscribe` answers an Effect `Stream`. The Promise bridge
// converts that to an `AsyncIterable` (`effect-adapter.mjs`), and a fake host
// answers an iterable directly. Any other value would make `for await` yield
// nothing at all — no error, no events — and the adapter would then never see a
// `session.created`, which is the fact that tells it a session is genuinely new.
// So the shape is decided here, once, and an unusable one is reported instead of
// being consumed as an empty stream.
export const eventsOf = (subscription) => {
  if (subscription === null || subscription === undefined) {
    return { ok: false, reason: "NO_EVENT_SUBSCRIPTION" };
  }
  if (typeof subscription[Symbol.asyncIterator] === "function") {
    return { ok: true, iterable: subscription };
  }
  if (typeof subscription[Symbol.iterator] === "function") {
    return { ok: true, iterable: subscription };
  }
  return { ok: false, reason: "UNSUPPORTED_EVENT_STREAM", detail: typeof subscription };
};

// The parent fact of a session, asked for as late as possible.
//
// The `prompt` hook may run before the event pump has drained that session's
// `session.created` (the pump is asynchronous and the host does not order the
// two), so the table alone answers "unknown" for a session that is genuinely
// top-level. When the table has no entry and the host can report the session, the
// host's own `get` answers `parentID`, and the answer is recorded in the table.
//
// Only a host that answers becomes a fact: a missing method, a failed call or a
// session the host does not know all stay `undefined`, which the receipt verdict
// reads as "parent unknown" and therefore does not capture.
const parentFactOf = async (activation, sessionID) => {
  const known = activation.parents.get(sessionID);
  if (known !== undefined) return { parentID: known, source: "table" };
  const sessions = activation.sessions;
  if (sessions === null || typeof sessions.get !== "function") {
    return { parentID: undefined, source: "NO_SESSION_GET" };
  }
  try {
    const answer = await sessions.get({ sessionID });
    const session = answer?.data ?? answer ?? null;
    const parentID = session?.parentID;
    const fact = typeof parentID === "string" && parentID.length > 0 ? parentID : null;
    activation.parents.set(sessionID, fact);
    activation.observedSessions.add(sessionID);
    return { parentID: fact, source: "host" };
  } catch (error) {
    return { parentID: undefined, source: "SESSION_GET_FAILED", detail: String(error?.message ?? error) };
  }
};

// The worker role of a session the adapter forked, or null. It is the fallback
// identity for a completion event that names no agent: a worker's turn ending is
// its report to its Manager and is never nudged, so answering "worker" is the
// honest reading of a session this adapter started as one.
const workerRoleOf = (activation, sessionID) => {
  for (const table of activation.workers.values()) {
    const worker = table.workerOfSession(sessionID);
    if (worker === null) continue;
    if (worker.role === "coder" || worker.role === "devops") return worker.role;
  }
  // A lane runs its owner's identity (§4.2), which the registration recorded
  // when the lane was created: a devops's lanes answer "devops" here, not the
  // default worker identity.
  const lane = [...activation.lanes.values()].find((item) => item.sessionID === sessionID);
  if (lane !== undefined) return lane.role ?? laneRoleOf(activation, lane.owner ?? null);
  // A fork-started worker whose table this activation has not rebuilt yet still
  // proves its identity: the origin entry is written when the child starts.
  const origin = activation.fissionOrigins?.get(sessionID);
  return origin?.role === "devops" || origin?.role === "coder" ? origin.role : null;
};

// The last assistant text of a request copy, for the fission owner view.
const lastTextOf = (messages) => {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message?.role !== "assistant") continue;
    const parts = Array.isArray(message.content) ? message.content : [];
    const text = parts
      .filter((part) => part?.type === "text" && typeof part.text === "string")
      .map((part) => part.text)
      .join("\n\n");
    if (text !== "") return text;
  }
  return null;
};

// The text of a completed worker's last answer, for the fission owner view and
// for the Plan's devops report. The host stores messages; a `get`/`context`
// answer is read defensively, because its exact shape is a host fact.
const lastAnswer = async (sessions, sessionID) => {
  if (sessions === null || typeof sessions.context !== "function") return null;
  let messages;
  try {
    messages = await sessions.context({ sessionID });
  } catch {
    return null;
  }
  const list = Array.isArray(messages) ? messages : (messages?.data ?? []);
  for (let index = list.length - 1; index >= 0; index -= 1) {
    const message = list[index];
    if (message?.role !== "assistant") continue;
    const parts = Array.isArray(message.content) ? message.content : [];
    const text = parts
      .filter((part) => part?.type === "text" && typeof part.text === "string")
      .map((part) => part.text)
      .join("\n\n");
    if (text !== "") return text;
  }
  return null;
};

// The workspace root, as one absolute path.
//
// Everything the adapter persists — `.Herdchestra/` journals, scope rows, the
// operator's original input — is keyed off this one value, so it is resolved
// once, here. The host may hand `location.directory` as a relative path, or omit
// it and leave the process cwd (which on the host is the CLI's own package
// directory, not the operator's workspace). A relative path is resolved against
// the cwd; a missing one falls back to the cwd. The result is absolute, so a
// writer and a reader that both start from it cannot end up in two directories.
export const rootOf = (ctx) => {
  const given = typeof ctx?.location?.directory === "string" ? ctx.location.directory : "";
  const base = given.length > 0 ? given : process.cwd();
  return path.resolve(base);
};

// The workspace the operator states for this run, or null.
//
// The host's plugin context describes the plugin host, and on a live host even
// the session's own record reported the server's launch directory — so nothing
// the API offers names the workspace the operator opened. `HERDCHESTRA_ROOT`
// (or the plugin option `root`) is the operator's own answer and wins over every
// host answer. It is validated the same way as any other root: resolved to an
// absolute path, never used as a relative string.
export const statedRoot = (ctx) => {
  const option = typeof ctx?.options?.root === "string" ? ctx.options.root : "";
  const fromEnv = typeof process.env.HERDCHESTRA_ROOT === "string" ? process.env.HERDCHESTRA_ROOT : "";
  const stated = option.length > 0 ? option : fromEnv;
  return stated.length > 0 ? path.resolve(stated) : null;
};

// The plugin host's root, as the best static answer.
//
// Measured on a live host, this is NOT the workspace the operator opened:
// `location.directory` was the CLI package dir (the server ran with
// `--cwd packages/cli`) and `location.project.directory` was the checkout the
// server was launched from. The context describes the plugin host, not the
// session. It is kept as the fallback for a host that cannot answer for a
// session; the session's own `location` wins whenever it can (`sessionRoot`).
export const workspaceRootOf = (ctx) => {
  // The operator's statement comes first, exactly as it does in
  // `resolveSessionRoot`; otherwise this function would promise "the workspace"
  // and answer "where the host was launched from" whenever the two differ.
  const stated = statedRoot(ctx);
  if (stated !== null) return stated;
  const project = ctx?.location?.project;
  const directory = typeof project?.directory === "string" ? project.directory : "";
  return path.resolve(directory.length > 0 ? directory : rootOf(ctx));
};

export const setup = async (ctx) => {
  const root = workspaceRootOf(ctx);
  const rootOverride = () => statedRoot(ctx);
  const sessions = ctx.session ?? null;
  // The workspace of one session, asked of the host. The plugin context
  // describes the plugin host (its launch directory), not the session; measured
  // live, both `location.directory` and `location.project.directory` were the
  // launch directory. The session's own record names the workspace the operator
  // opened, so it wins whenever the host reports it; the plugin root is the
  // fallback for a host that does not.
  const sessionRoot = async (sessionID) => {
    const sessionsApi = ctx.session;
    if (sessionsApi === null || typeof sessionsApi?.get !== "function") return root;
    try {
      const answer = await sessionsApi.get({ sessionID });
      const session = answer?.data ?? answer ?? null;
      const reported = session?.location?.directory ?? session?.location?.project?.directory;
      if (typeof reported === "string" && reported.length > 0) return path.resolve(reported);
    } catch {
      // A host that cannot answer keeps the plugin root; the trace reports it.
    }
    return root;
  };
  // A session's root is resolved once, when its state is first built, so the
  // writer and every later reader of its `.Herdchestra` agree on one path. It
  // must be resolved BEFORE the state is built, because the state is what holds
  // the root: `resolveSessionRoot` asks the host and remembers the answer.
  const roots = new Map();
  const resolveSessionRoot = async (sessionID) => {
    const known = roots.get(sessionID);
    if (known !== undefined) return known;
    // The operator's own answer wins over every host answer. Measured live, a
    // host can report the server's launch directory as the session's location,
    // and nothing in the API names the workspace the operator opened; when the
    // operator states it, that statement is the fact.
    const stated = rootOverride();
    const resolved = stated ?? (await sessionRoot(sessionID));
    roots.set(sessionID, resolved);
    return resolved;
  };
  const rootOfSession = (sessionID) => roots.get(sessionID) ?? root;

  // `fork(name, verboseIntent)` (§4.1). A live name steers; a new name starts a
  // child session whose completion the host delivers to the parent; a finished
  // worker can be reused in place (root §1.1 item 3).
  //
  // The worker's identity comes from the task text's own declaration (§2.1):
  // compose resolves it and hands it over as `role`, and a caller that arrives
  // without one (Plan's `devops` primitive) declares it here. Nothing defaults
  // to coder, and nothing infers an identity from the worker's name.
  //
  // `model` is only for internal callers. The model-facing `fork` tool has no
  // such input (root §1.1 item 6): the task model, or the parent's, is chosen
  // here, never by the model.
  const forkWorker = async ({
    sessionID,
    name,
    verboseIntent,
    role = null,
    model,
    wait = false,
    reuse = false,
    keepRoad = false,
  }) => {
    if (sessions === null) return refusal(REFUSALS.GAP, { gap: "FORK_SESSIONS" });
    // The operator stopped the work this session belongs to: no new worker and
    // no steer may resurrect it (root §1.1 item 2).
    if (activation.cancelled?.has(sessionID) === true) return refusal(REFUSALS.CANCELLED, { sessionID });
    // The identity is decided BEFORE the first await: a devops road has to be
    // reserved synchronously, or two concurrent forks both see an empty road
    // and two devops run at once (§2.3).
    let declared = role;
    if (declared === null || declared === undefined) {
      try {
        declared = forkRequest(name, verboseIntent).role;
      } catch (error) {
        return refusal(REFUSALS.ROLE_ARG, { reason: error.details?.reason ?? "UNDECLARED_ROLE", message: error.message });
      }
    }
    const picked = workerRole(declared);
    if (picked === null) return refusal(REFUSALS.ROLE_ARG);
    const table = workerTableOf(activation, sessionID);
    // A same-name fork of a LIVE worker is a steer (§2.3), not a new devops:
    // the bound devops is exactly what a steer is for, so it never meets the
    // road guard.
    const live = table.get(name);
    if (live !== null && live.cancelled === true) return refusal(REFUSALS.CANCELLED, { name });
    const steering = live !== null && live.state === "running";
    // A finished worker may be reused in place. It is not a steer (the session
    // is idle) and it is not a new devops, so it must not meet the road guard:
    // the name is already the one this road holds.
    const reusing = reuse === true && live !== null && !steering;
    const reserve = picked === "devops" && !steering && !reusing;
    const road = devopsRoadOf(activation, sessionID);
    if (reserve) {
      // A devops the journal restored may have finished while no plugin was
      // loaded. The table's first use reconciles it against the host, and a
      // finished one has released the road; keeping it in the set would refuse
      // every later devops for a worker that is no longer running.
      for (const held of [...road]) {
        const worker = table.get(held);
        if (worker === null || worker.state !== "running") road.delete(held);
      }
      const busy = devopsRefusal(road);
      if (busy !== null) return busy;
      // Reserved before any await, so a second fork of this session sees it.
      road.add(name);
    }
    const release = () => {
      if (reserve) road.delete(name);
    };
    // The child runs the TASK model when the operator configured one, otherwise
    // the parent's model (`model-inherit.mjs`). The host does not inherit a
    // model for a created child, and the agent default is the free model that
    // answers 429. A caller that names a model keeps it; that path is internal,
    // because the model-facing `fork` has no model input. A parent without a
    // model is a refusal rather than a silent default.
    let inherited = model;
    let modelSource = inherited === undefined || inherited === null ? null : "caller";
    // A steer or a reuse creates no session, so it needs no model and must not
    // be refused for a parent that has none: the worker already exists and
    // already runs. Only a new session has a model to choose.
    if (!reusing && !steering && (inherited === undefined || inherited === null)) {
      const found = await resolveChildModel({ sessions, parentID: sessionID });
      if (found.ok !== true) {
        release();
        return found.refusal ?? refusal(REFUSALS.TASK_MODEL_INCOMPLETE, { detail: found.detail ?? null });
      }
      inherited = found.model;
      modelSource = found.modelSource;
    }
    const create = reusing
      ? { parentID: sessionID, title: name, agent: picked }
      : createInput({ parentID: sessionID, name, role: picked, model: inherited });
    if (create.rejection !== undefined) {
      release();
      return create;
    }
    let receipt;
    try {
      receipt = await table.fork({ ...create, verboseIntent, role: picked, title: name, reuse: reusing });
    } catch (error) {
      release();
      throw error;
    }
    if (receipt.cancelled === true) return refusal(REFUSALS.CANCELLED, { name });
    // The reservation stands only while the worker this fork STARTED runs; a
    // steer leaves the road exactly as it was.
    if (receipt.started !== true) release();
    // A dedicated worker keeps its road after the answer, so the next
    // investigation reaches the same session (root §1.1 item 3).
    if (keepRoad && picked === "devops") road.add(name);
    // The child is a fork-started worker: only such a program may fission
    // (§4.2), and only it keeps the worker identity for its lanes. The key is
    // the child's SESSION id, because that is what a later `fission` call
    // resolves (`ownerName()` on the calling session's port) and what
    // `fissionTableOf` asks `origins` about.
    if (receipt.started) {
      const child = table.sessionIDOf(receipt.name);
      if (typeof child === "string") {
        activation.fissionOrigins.set(child, { role: picked, parent: sessionID });
      }
    }
    if (!wait) return forkReceipt({ ...receipt, modelSource });
    // Plan's `devops` primitive waits for the one answer (§6): it is
    // synchronous by contract, and the report is returned whole.
    const child = table.sessionIDOf(receipt.name);
    try {
      // A worker the journal restored, or one steered while it still carried
      // the outcome of its previous turn, may already be finished. `Session.wait`
      // resolves when the process owns no active execution for the session, so
      // asking it about a finished session can wait for a run that will never
      // come; the host's own record is what decides, and only a running session
      // is waited on.
      const current = await sessions.get({ sessionID: child });
      const session = current?.data ?? current ?? null;
      if (settledOutcomeOf(session).settled !== true) await sessions.wait({ sessionID: child });
      const report = await lastAnswer(sessions, child);
      if (table.isCancelled(receipt.name)) {
        return refusal(REFUSALS.CANCELLED, { name: receipt.name, report });
      }
      return { ok: true, name: receipt.name, report, modelSource };
    } finally {
      // Only a worker this fork STARTED holds the road this call reserved. A
      // steer (`started: false`) belongs to the live devops the reservation was
      // never taken for; releasing here would free a road that is still in use.
      // A dedicated worker (`keepRoad`) keeps its name across investigations.
      if (receipt.started === true && !keepRoad) road.delete(receipt.name);
    }
  };

  const joinWorker = async ({ sessionID }) => {
    // `workerTableOf`, never `activation.workers.get`: a successor's table is
    // created on first use, and creating it is what adopts the predecessor's
    // roster (§5.2). Reading the raw map answered `PROGRAM_BUSY` — "the named
    // program is still running" — for a session that simply had no table yet,
    // so the first `join` of a relayed successor never saw the results the
    // record preserved.
    const table = workerTableOf(activation, sessionID);
    const record = await table.join();
    if (record !== null) devopsRoadOf(activation, sessionID).delete(record.name);
    return joinReceipt({ record });
  };

  const activation = makeActivation({
    sessions,
    root,
    hooks: { forkWorker, joinWorker, rootOfSession },
  });
  activation.resume = makeResumer(activation, (sessionID) => stateOf(activation, sessionID));
  // The operator's explicit language, if the host passes one in the plugin
  // options; the ladder in core decides the rest.
  activation.explicitLanguage = ctx.options?.language ?? null;

  // One PairHint per session, opened lazily from the session's journal. The
  // journal is the fact the hint replays from, so a restart resumes the same
  // epoch, language and handles (§6.1).
  const hints = new Map();
  // Throws when the hint cannot be opened or bound. A request without the hint
  // would silently drop a spec obligation (§6.1: a missing book is a failure,
  // not a substitute), so the failure reaches the host instead of becoming a
  // quiet no-op.
  const hintFor = async (activation, sessionID) => {
    const existing = hints.get(sessionID);
    if (existing !== undefined) return existing;
    if (typeof sessionID !== "string" || sessionID.length === 0) {
      throw new TypeError(`context hook got no session id: ${typeof sessionID}`);
    }
    // `root` is the workspace the host loaded the plugin for; without it the
    // journal has nowhere to live, and a hint over no journal would silently
    // rebuild from memory and lose the epoch on restart (§6.1).
    if (typeof root !== "string" || root.length === 0) {
      throw new TypeError("the host gave no workspace root; the Pair Hint journal needs one");
    }
    const hint = await openPairHint({ root, sessionId: sessionID });
    // The hint speaks the session's language. `bind` is a no-op when the
    // journal already carries a binding, so a restart cannot move a session
    // that already spoke (§6.1).
    if (!hint.bound) {
      // The session's binding is the authority; the ladder runs only for a
      // session that has none yet, and `bindLanguage` records the result so the
      // next request reuses it.
      const state = stateOf(activation, sessionID);
      // A relayed successor has no binding of its own yet (根 §2.4 第 3 步): its language
      // comes from the tenure the relay record names, and it is bound once here
      // so the successor's first request already speaks it. The inherited
      // binding is read without opening the successor's Scope, which happens
      // later in this same request.
      const binding = state.languageBinding ?? inheritedBinding(root, sessionID);
      // The journal's own name is the key (`openPairHint` opens it under the
      // digest of the host id), so the hint is always bound under that name:
      // `PairHint.load` refuses a journal whose binding names another session.
      // The language is the fact being carried; a fork inherits it, a top-level
      // session has already resolved it once and re-resolves nothing here.
      if (binding !== null && binding !== undefined) {
        await hint.bind({ sessionId: hint.sessionId, forkOf: binding });
      } else {
        const resolved = state.bindLanguage({
          ladder: { explicit: activation.explicitLanguage ?? null, host: null, locale: null },
        });
        await hint.bind({ sessionId: hint.sessionId, ladder: { explicit: resolved.language } });
      }
    }
    hints.set(sessionID, hint);
    return hint;
  };

  // Fields the host would not let this adapter write. Reported with the plugin
  // facts (`hostFacts`), because a silently unwritten prompt or mode would look
  // like a working agent.
  const readonlyAgentFields = [];
  activation.readonlyAgentFields = readonlyAgentFields;

  // The host's `Agent.Info` is a Schema class, so a field it declares is an
  // accessor: assigning to one dies with `TypeError: Attempted to assign to
  // readonly property.` That throw happens inside the host's transform runner,
  // and the host attributes it to whichever plugins are still in the chain —
  // live, `opencode.config.agent` and `opencode.browser` were disabled by it
  // (2026-11-01, `plugin.id=opencode.config.agent state=agent`).
  //
  // So nothing here assigns a field by name. `applyAgentFields` replaces the
  // values a field already holds (a list is emptied and refilled, never
  // reassigned), which is the one shape that works whether the host hands over
  // a plain object or a decoded one.
  //
  // A scalar the host owns (a string, a boolean behind a getter) cannot be
  // replaced in place at all. It is written only when the object really accepts
  // a write; when it does not, the host's own value stands and the field is
  // reported instead of pretending. Every host agent is created by
  // `Agent.Info.default(id)` (`agents.get(l) ?? B1.default(l)` in the host's
  // editor), so the fields the adapter must own are writable in practice; the
  // guard exists so a host that freezes them degrades to a named gap, not to a
  // transform failure that disables other plugins.
  //
  // A host field's CURRENT value, read without triggering a getter that may
  // throw. A refused read is not a value: `undefined` means "the adapter does
  // not know", and the write below still gets attempted.
  const valueOf = (target, key) => {
    try {
      return target[key];
    } catch {
      return undefined;
    }
  };

  const applyAgentFields = (target, fields) => {
    for (const [key, value] of Object.entries(fields)) {
      // EVERY read of a host field goes through `valueOf`. A `Schema` class can
      // expose a getter that throws (`Agent.Info.mode` on 2026-11-01), and a
      // bare `target[key]` outside the try is an exception that escapes the
      // transform and makes the host disable the whole plugin chain — which is
      // exactly the failure this guard exists to prevent.
      const current = valueOf(target, key);
      if (key === "permissions" && Array.isArray(current)) {
        current.splice(0, current.length, ...value);
        continue;
      }
      if (Array.isArray(current) && Array.isArray(value)) {
        current.splice(0, current.length, ...value);
        continue;
      }
      // A descriptor cannot tell a writable accessor from one that throws: the
      // host's Schema class exposes `set` on a field and still refuses the
      // write. So the write is attempted, and a refusal is a fact reported
      // rather than an exception that disables the plugin.
      try {
        target[key] = value;
      } catch {
        readonlyAgentFields.push(`${target.id ?? "?"}.${key}`);
      }
    }
    return target;
  };

  await ctx.agent.transform((editor) => {
    for (const [id, definition] of Object.entries(AGENT_DEFINITIONS)) {
      editor.update(id, (agent) => applyAgentFields(agent, definition));
    }
    for (const agent of editor.list()) {
      if (AGENT_DEFINITIONS[agent.id] !== undefined) continue;
      if (OTHER_AGENTS_HIDDEN) editor.update(agent.id, (item) => applyAgentFields(item, { hidden: true }));
    }
  });

  // A program call: the surface is built for the calling session's state.
  //
  // The executor receives the host's call context and states it per call
  // (`activation.portsFor`); nothing is stored on the activation for the
  // duration of the call, so two interleaved tool calls of different sessions
  // cannot read each other's relay or devops context.
  const programFor =
    (role, options = {}) =>
    (input, context) =>
      makeProgramExecutor(activation, role, { session: (id) => stateOf(activation, id), ...options })(input, context);

  // `js-worker` is the one tool two identities share (§7.1): `coder` and
  // `devops` run the same surface under their own role. The role is read from
  // the calling session, never fixed by the registration: a fixed `coder` made
  // every `devops` call answer `ROLE_DENIED` (`tool: "coder"`), which is the
  // `execute-hooks` layer-one owner list contradicted one layer deeper. The
  // executor still checks the role it is given, so a caller outside the worker
  // identities is refused there, and a null role reaches that same refusal.
  // The owner's identity for the duration of one call: a lane this call creates
  // is a parallel present of THIS worker (§4.2), so a devops that fissions
  // starts devops lanes. The identity is a property of the SESSION (its agent),
  // so it is recorded by session id, not in a slot "for the duration of the
  // call": two sessions never overwrite each other's identity.
  const workerProgram = (input, context) => {
    const role = workerRole(roleOf(context.agent));
    if (role !== null) activation.laneRoles.set(context.sessionID, role);
    return programFor(role)(input, context);
  };

  const forkTool = async (input, context) => {
    // §4.1: every dispatching role may fork; Plan never does.
    if (!ownsTool(roleOf(context.agent), TOOL_NAMES.fork)) return refusal(REFUSALS.ROLE, { tool: "fork" });
    return forkWorker({
      sessionID: context.sessionID,
      name: input?.name ?? input?.workerName,
      verboseIntent: input?.verboseIntent,
      // No `role` from the model: §4.1 gives `fork` two arguments, and the
      // verboseIntent declares the identity. `input.role` is not read.
      //
      // No `model` either (root §1.1 item 6): the task model or the parent's is
      // chosen by the adapter, so a model cannot choose its own.
    });
  };

  const joinTool = async (_input, context) => {
    if (!ownsTool(roleOf(context.agent), TOOL_NAMES.join)) return refusal(REFUSALS.ROLE, { tool: "join" });
    return joinWorker({ sessionID: context.sessionID });
  };

  await ctx.tool.transform((editor) => {
    registerTools(editor, {
      [TOOL_NAMES.manager]: programFor("manager"),
      [TOOL_NAMES.worker]: workerProgram,
      // `js-review` is the Manager's separate read-only face (§8): the caller
      // is a Manager, but the body gets `reviewSurface()`, not `js-manager`.
      [TOOL_NAMES.review]: programFor("manager", { review: true }),
      [TOOL_NAMES.plan]: programFor("plan"),
      [TOOL_NAMES.fork]: forkTool,
      [TOOL_NAMES.join]: joinTool,
    });
    // §7.1's static half: a host builtin no role may use is removed from the
    // registry itself, so a request path that advertises the catalog without
    // running the `context` hook cannot offer it. The per-request narrowing
    // (`narrowTools`) remains the Manager's own half; this list is only what
    // every agent forbids (`STATIC_REMOVED_TOOLS`).
    if (typeof editor.remove === "function") {
      for (const name of STATIC_REMOVED_TOOLS) editor.remove(name);
    }
  });

  await ctx.tool.hook("execute.before", (event) => {
    // A tool call proves the identity the session runs as, exactly as a request
    // does. The turn-exit controller reads this table, and a completion event
    // names only the session — so a session that has called a tool but not yet
    // reached the `context` hook must still be recognised as Plan or Manager.
    if (typeof event.agent === "string") activation.agents.set(event.sessionID, event.agent);
    const stopped = beforeCall(event, { state: stateOf(activation, event.sessionID) });
    // The host has no `skip` channel for this hook, so a refused call is a
    // thrown error. The code leads, so the failure the host records names the
    // reason (`RELAY_RETIRED`, `SCOPE_REFUSED`, ...) and not only the sentence.
    //
    // `detail` is carried too. A scope refusal's table sentence is deliberately
    // generic ("the annotation was refused"), but the book already worked out
    // the concrete fact — `scope.resetScope: newScopeGoal must be a non-blank
    // string` — and dropping it left the model with nothing to correct. The
    // host runs this hook BEFORE it decodes the arguments against the input
    // schema (`packages/core/src/tool.ts` `beforeExecute` precedes `executeTool`
    // → `decodeInput`), so this throw is the only chance to tell the model what
    // is wrong; the schema's own message never gets a turn.
    if (stopped !== null) {
      const detail = typeof stopped.detail === "string" && stopped.detail.length > 0 ? ` (${stopped.detail})` : "";
      throw new Error(`${stopped.rejection}: ${stopped.message}${detail}`);
    }
  });

  await ctx.tool.hook("execute.after", async (event) =>
    afterCall(event, {
      state: stateOf(activation, event.sessionID),
      turnId: event.messageID,
    }),
  );

  const contextHandler = async (event) => {
    await resolveSessionRoot(event.sessionID);
    // The identity a session runs as, as its requests report it. A completion
    // event names the session and not the agent, so the turn-exit controller
    // reads this table (turn-nudge.mjs).
    if (typeof event.agent === "string") activation.agents.set(event.sessionID, event.agent);
    const state = stateOf(activation, event.sessionID);
    // A session that reaches a request before its `session.created` event is
    // still a session, and the hint needs a language. The state binds once and
    // keeps it; a binding from an earlier process or from the parent wins
    // (§6.1), and the hint reads that same binding below instead of resolving
    // the ladder a second time.
    // The last completed answer is the owner view a fission lane receives.
    if (Array.isArray(event.messages) && event.messages.length > 0) {
      const view = lastTextOf(event.messages);
      if (view !== null) state.lastView = view;
    }
    // One PairHint per session, opened from that session's journal. The hint
    // owns the epoch, the pair position and the prefix chain (§6.1); the
    // context hook only hands it the projected copy.
    //
    // A hint that cannot be opened (a journal that will not read, a resource
    // pair with a missing book) is a §6.1 failure, not a request without a
    // hint: it is thrown so the host fails the execution instead of sending an
    // unprojected request. The host gives this hook no failure channel of its
    // own (`packages/core/src/plugin/hooks.ts`), so a throw is the only way to
    // stop the request.
    const hint = await hintFor(activation, event.sessionID).catch((error) => {
      throw new ContextRefusal(error?.code ?? "PAIR_HINT_UNAVAILABLE", "the Pair Hint could not be opened", {
        detail: String(error?.message ?? error),
      });
    });
    // A request proves the session exists and is one this activation serves, so
    // it is recorded as observed: that is what lets the session's first operator
    // prompt capture the original input even when the event pump has not drained
    // that session's `session.created` yet.
    activation.observedSessions.add(event.sessionID);
    const applied = await applyContextHook(event, { state, hint });
    // `context` runs once per model request, and one model request is one
    // model turn (§6.2). Every tool call of that turn is admitted and
    // settled through `execute.before`/`execute.after` before this hook runs
    // again, so the turn is closed here — at the turn boundary, not per tool
    // call: closing after a single tool would book a turn whose parallel calls
    // have not been seen yet, and a turn that only staged `meToo` would then be
    // refused while its true annotation is still on its way.
    // The refusal is reported with the request result, not thrown: the request
    // itself is still well formed, and the model must see why its turn was not
    // booked (§6.2). The turn is closed inside `applyContextHook`, exactly
    // once per request; this hook only returns what it decided.
    return applied;
  };

  // §7.1: the same projection runs on every request that borrows the
  // conversation. `context` is the session's own model request; `compaction`
  // summarises it; `generate` is a one-shot branch built from the history.
  // A request that skipped the projection would be sent without the Pair Hint,
  // the Scope projection or the relay barrier — exactly what §6.1 forbids.
  await ctx.session.hook("context", contextHandler);
  await ctx.session.hook("compaction", contextHandler);
  await ctx.session.hook("generate", async (event) => {
    // A generate request is a BRANCH of the chain, not a step of it: it must not
    // overwrite the prefix the main conversation presented. The marker is set
    // before the projection; a host that already named the kind keeps it.
    if (event !== null && typeof event === "object" && event.kind === undefined) {
      try {
        event.kind = "ephemeral";
      } catch {
        // A frozen event keeps its own (unmarked) kind; `requestKind` reads the
        // absence as `main`, the stricter behaviour.
      }
    }
    return contextHandler(event);
  });

  await ctx.session.hook("prompt", async (event) => {
    // The session's workspace is resolved before its state exists: the state
    // carries the root every fact of this session is written under.
    await resolveSessionRoot(event.sessionID);
    if (typeof event.agent === "string") activation.agents.set(event.sessionID, event.agent);
    const state = stateOf(activation, event.sessionID);
    const ledgers = new Map([[event.sessionID, state.ledgerFor("manager")]]);
    // The parent table is what makes the original-input capture safe: only a
    // session this table proves is top-level records the operator's words
    // (original-input.mjs). A child's first prompt is its brief (§4.1), and a
    // session whose parent is not known yet captures nothing.
    // The original-input verdict is a fact the operator and the adapter both
    // need to see: when a capture does not happen, the receipt says which fact
    // was missing (parent, delivery, brief id) instead of leaving it to be
    // guessed from an empty directory. `HERDCHESTRA_TRACE=1` also writes it to
    // stderr, because the host does not surface a hook's return value.
    const parent = await parentFactOf(activation, event.sessionID);
    const parentSeen = parent.source;
    // A session that was already running when this activation started predates
    // the original-input fact: its next prompt is not its first, so it is
    // marked unrecorded once and never captures (original-input.mjs). A host
    // that reports the session's parent has proven it is a session this
    // activation can see, so it is not treated as a stranger.
    const observed = parent.source === "host" || activation.observedSessions.has(event.sessionID);
    const predates = activation.startedAt !== null && !observed;
    // A session this adapter created was registered before its first prompt
    // (`identity.mjs`). That registration is the durable answer to "is this
    // receipt the operator's": it says whether the session is top-level, and it
    // exists even when the host's `session.created` has not arrived yet.
    const registered = activation.identities?.get(event.sessionID) ?? null;
    const reopened = applyPromptHook(event, {
      ledgers,
      state,
      parentID: parent.parentID,
      registeredParentID: registered === null ? undefined : registered.parentID,
      startedBeforeRecording: predates,
    });
    const answered = await answerPendingAsk({
      event,
      activation,
      state,
      sessionID: event.sessionID,
      resume: activation.resume,
      parentID: parent.parentID,
      registeredParentID: registered === null ? undefined : registered.parentID,
    });
    if (process.env.HERDCHESTRA_TRACE === "1") {
      process.stderr.write(
        `${JSON.stringify({
          herdchestra: "original-input",
          sessionID: event.sessionID,
          reason: reopened.original?.reason ?? null,
          captured: reopened.original?.captured ?? null,
          parent: parentSeen,
          // The resolved workspace root. The writer and the reader must agree on
          // this one path; printing it is what makes a disagreement visible
          // instead of showing up as "captured: true but no file".
          // The root the writer actually used for THIS session, which is not
          // the plugin-host root: the stated root and the session's own
          // location are resolved per session. Both are printed so a
          // disagreement is visible instead of being read as agreement.
          root: rootOfSession(event.sessionID),
          hostRoot: activation.root ?? null,
          detail: parent.detail ?? reopened.original?.detail ?? null,
        })}\n`,
      );
    }
    return {
      ...reopened,
      answered,
      // `original.reason` is the verdict; `parent` says how the parent fact was
      // learned ("table" | "host" | "NO_SESSION_GET" | "SESSION_GET_FAILED").
      ...(reopened.original?.captured === false
        ? { originalInput: { reason: reopened.original.reason ?? null, parent: parentSeen } }
        : {}),
    };
  });

  const subscription = ctx.event.subscribe({
    types: [
      "session.created",
      "session.execution.succeeded",
      "session.execution.failed",
      "session.execution.interrupted",
    ],
  });
  // The host's `event.subscribe` answers an Effect `Stream`; the Promise bridge
  // (`effect-adapter.mjs`) converts it to an `AsyncIterable`. A fake host answers
  // an iterable directly. `for await` over anything else silently yields nothing,
  // which would leave the pump idle and mark every session unrecorded, so the
  // two shapes are accepted explicitly and anything else is reported.
  const events = eventsOf(subscription);
  const pump = (async () => {
    if (events.ok !== true) return;
    for await (const event of events.iterable) {
      const data = event?.data;
      if (data?.sessionID === undefined) continue;
      // Esc at a user-facing agent stops the work it started (root §1.1 item 2).
      // The guards in `cancel.mjs` decide whether this interrupt is really the
      // operator's: an `ask` park, a relay's silencing of the incumbent and a
      // fission present replacement do not propagate. A failed propagation is
      // reported by the sessions it could not stop, never by stopping the pump.
      if (event.type === "session.execution.interrupted") {
        try {
          await propagateCancellation({ activation, sessionID: data.sessionID, agent: data.agent ?? null });
        } catch {
          // The next interrupt of the same session is judged on its own facts.
        }
      }
      if (event.type !== "session.created") {
        // A finished worker stops being steerable; its name may start a new
        // session later (§4.1).
        const outcome =
          event.type === "session.execution.succeeded"
            ? "succeeded"
            : event.type === "session.execution.failed"
              ? "failed"
              : "interrupted";
        for (const table of activation.workers.values()) table.settled(data.sessionID, outcome);
      }
      // A natural turn end of a user-facing session is a stop the agent did not
      // earn, unless it is Plan's `S1` question or the verified completion
      // (根 §2.2). The controller decides with the session's own facts and
      // answers with a nudge prompt; a worker's turn ending is its report and
      // is not read here. The worker bookkeeping runs first, so a session the
      // roster just settled answers its identity from that same roster.
      if (event.type === "session.execution.succeeded" || event.type === "session.execution.failed") {
        if (typeof data.agent === "string") {
          activation.agents.set(data.sessionID, data.agent);
        } else {
          // The event names no agent; the session's roster does. A worker's
          // completion is not nudged, and reading the roster keeps a missing
          // `agent` field from being read as a user-facing session.
          const worker = workerRoleOf(activation, data.sessionID);
          if (worker !== null) activation.agents.set(data.sessionID, worker);
        }
        try {
          await handleTurnEnd({ activation, sessionID: data.sessionID, event });
        } catch {
          // A nudge that cannot be decided is reported by its own absence, not
          // by stopping the pump: the next exit of the session is judged on its
          // own facts.
        }
      }
      if (event.type !== "session.created") continue;
      // A new session binds its language once: a child copies its starter's,
      // a top-level session resolves the ladder (§6.1). The starter is
      // resolved from the event's parent id, whichever order the events arrive.
      // The parent id is recorded first, because the operator's original input
      // is captured on the first prompt, and that prompt may arrive before this
      // event is pumped (original-input.mjs reads this table).
      activation.parents.set(data.sessionID, data.parentID ?? null);
      activation.observedSessions.add(data.sessionID);
      stateOf(activation, data.sessionID).bindLanguage({
        parentID: data.parentID ?? null,
        parent: data.parentID ? (activation.states.get(data.parentID)?.languageBinding ?? null) : null,
        ladder: { explicit: ctx.options?.language ?? null, host: null, locale: null },
      });
    }
  })().catch(() => {});

  const cleanup = async () => {
    hints.clear();
    // The subscription is an iterator whose `return` is synchronous on a fake
    // host and a Promise on the host's bridge; both are released the same way.
    try {
      await events.iterable?.return?.();
    } catch {
      // A release that fails must not stop the rest of the cleanup.
    }
    void pump;
    disposeActivation(activation);
  };
  // The host fields this adapter could not write, named. A caller that wants the
  // facts (`hostFacts`) or a test that pins the degradation reads them here;
  // nothing else in the adapter does.
  cleanup.readonlyAgentFields = readonlyAgentFields;
  // The activation is the setup's whole state. A caller that must inspect a
  // fact the host keeps (the close shape of a session, for one) reads it from
  // here instead of rebuilding a second state that could disagree.
  cleanup.stateOf = (sessionID) => stateOf(activation, sessionID);
  // What the activation knows about a session's origin: the parent id from its
  // `session.created` event, and whether the activation ever saw that session.
  // A caller deciding whether a receipt is the operator's first needs both, and
  // a rebuilt table would not hold them.
  cleanup.observeSession = (sessionID, { parentID = null } = {}) => {
    activation.parents.set(sessionID, parentID);
    activation.observedSessions.add(sessionID);
  };
  // The relay host is exposed for the same reason: its refusal reason is a fact
  // the tests assert, and a rebuilt one would not share the journal.
  cleanup.relayHost = activation.relayHost;
  // The worker tables and the road-bound devops set are the facts a relay
  // preserves (根 §2.4). A test that asserts adoption reads them here rather than
  // rebuilding a second activation that could disagree.
  cleanup.workerTableOf = (sessionID) => workerTableOf(activation, sessionID);
  cleanup.devopsInFlight = activation.devopsInFlight;
  // The turn-exit controller's own facts: the identity each session reported
  // and the rounds already booked. A test that asserts one round is nudged once
  // reads them here instead of inferring them from prompts.
  cleanup.activation = activation;
  return cleanup;
};

export const herdchestra = definePlugin(setup);

export { hostFacts, openGaps };

export default herdchestra;
