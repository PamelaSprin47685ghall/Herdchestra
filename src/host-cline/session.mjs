// One Cline session's adapter state (spec §3.3, §5, §6, §6.1, §6.2, §7.2).
//
// The session object is what the hooks and the tools share:
//
//   - the program registry, the park table and the control surface (core);
//   - the worker table behind `fork`, `join` and fission lanes;
//   - the Manager tenure: review ledger, relay fence, Scope and Pair Hint books;
//   - the ports compose reads, each one a real host capability or an honest
//     refusal.
//
// No private class members: the host imports plugin modules through jiti, whose
// transform does not accept `#name()` methods, and the adapter must load there.

import path from "node:path";
import { createHash } from "node:crypto";

import { ProgramRegistry } from "../core/program.mjs";
import { ControlSurface } from "../core/control.mjs";
import { systemClock as clock } from "../core/clock.mjs";
import { ROLES, ProgramSurface } from "../compose/program/surface.mjs";
import { ReviewMember } from "../compose/relay/review.mjs";
import { systemPromptFor } from "../compose/prompt/projection.mjs";
import { directorPromptFor } from "../compose/prompt/director.mjs";
import { PLAN_PROMPT } from "../compose/prompt/plan.mjs";

import { ParkTable } from "./execution.mjs";
import { WorkerTable } from "./workers.mjs";
import { TenureState } from "./tenure.mjs";
import { commandPort, relayPort } from "./ports.mjs";
import { appendJsonLine } from "./files.mjs";
import { SessionFission } from "./fission.mjs";
import { runtimeHandle } from "./runtime.mjs";
import { guardedDelegatedApi, providerCredentials } from "./host-modules.mjs";
import { ManagerSessions } from "./sessions.mjs";
import { ensureDraftDir, planDraftPath, planPorts, readDraft } from "./plan.mjs";
import { createDevopsRunner } from "./devops.mjs";
import { projectRoot, safeName, sessionIdOf, workspaceRootOf } from "./facts.mjs";
import { roleOf } from "./identity.mjs";
import { readRoleFact, writeRoleFact } from "./role-fact.mjs";
import { projectionBoundary, shouldAnchor } from "../compose/request-boundary.mjs";
import { REBASE_REASONS } from "../core/pair-hint/pair-hint.mjs";
import {
  PINNED_MEMBERS,
  barrierCutIndex,
  compactionDigest,
  isCompactedCopy,
  lastOperatorText,
  selectKept,
  successionNotice,
  toItems,
  toPairHintMessages,
  toCueToolResults,
} from "./request.mjs";

// The roles a session may hold. Anything else is a broken role string, and the
// adapter refuses it instead of opening a ledger on a guess.
const MARKABLE_ROLES = new Set([ROLES.MANAGER, ROLES.PLAN, ROLES.CODER, ROLES.DEVOPS]);

// One connection, or null. Both halves are required: a provider with no model
// starts a runtime that cannot connect, and a model with no provider has no
// account behind it. Every reader below funnels through this, so "complete" has
// exactly one definition.
const connectionOf = (providerId, modelId) => {
  if (typeof providerId !== "string" || providerId.trim().length === 0) return null;
  if (typeof modelId !== "string" || modelId.trim().length === 0) return null;
  return { providerId: providerId.trim(), modelId: modelId.trim() };
};

// The connection the setup context carries, in the shapes a host may use. The
// Cline 0.2.0 setup context carries none of them (measured), but an embedder
// and a test do, and reading them costs one property access each.
const modelFromContext = (ctx) => {
  if (ctx === null || typeof ctx !== "object") return null;
  const candidates = [
    [ctx.model?.providerId ?? ctx.model?.provider, ctx.model?.modelId ?? ctx.model?.model],
    [ctx.config?.providerId ?? ctx.config?.provider, ctx.config?.modelId ?? ctx.config?.model],
    [ctx.session?.providerId ?? ctx.session?.provider, ctx.session?.modelId ?? ctx.session?.model],
  ];
  for (const [providerId, modelId] of candidates) {
    const found = connectionOf(providerId, modelId);
    if (found !== null) return found;
  }
  return null;
};

// The connection the host's own session record carries. Cline stores it on the
// row and inside the metadata (`{ provider: "openai-compatible", model:
// "lazy/task" }` on a live Desktop session), so both places are read.
const modelFromRecord = (record) => {
  if (record === null || typeof record !== "object") return null;
  const metadata = record.metadata;
  const candidates = [
    [record.providerId ?? record.provider, record.modelId ?? record.model],
    [metadata?.providerId ?? metadata?.provider, metadata?.modelId ?? metadata?.model],
  ];
  for (const [providerId, modelId] of candidates) {
    const found = connectionOf(providerId, modelId);
    if (found !== null) return found;
  }
  return null;
};

// The connection an embedder pins in the environment. Both halves must be set:
// one alone is not a connection, and guessing the other would be a fabricated
// reading.
const modelFromEnvironment = () => connectionOf(process.env.CLINE_PROVIDER_ID, process.env.CLINE_MODEL_ID);

// The connection the operator pinned for SUBAGENTS (root §1.1 item 6): a task
// model is cheaper or faster than the main one, and starting every worker on
// the main model spends the wrong budget. Both halves are required, exactly as
// in every other reading: one env var alone is not a connection.
const modelFromTaskEnvironment = () =>
  connectionOf(process.env.HERDCHESTRA_TASK_PROVIDER_ID, process.env.HERDCHESTRA_TASK_MODEL_ID);

// A stable identity for one conversation that is NOT this session's main chain.
//
// A delegated runtime's first message is its verboseIntent; every later request of that
// same runtime repeats the conversation from its own beginning, so the opening
// message identifies it. Hashing keeps the key file-safe and short — the key
// names a Pair Hint epoch, and an epoch name reaches a file path.
const childKeyOf = (copy) => {
  const first = Array.isArray(copy) && copy.length > 0 ? copy[0] : null;
  let text;
  try {
    text = JSON.stringify(first ?? null);
  } catch {
    text = String(first);
  }
  return `child-${createHash("sha256").update(text).digest("hex").slice(0, 16)}`;
};

// The shapes of one request's items, as a Scope refusal records them.
//
// A digest cannot be read back and a length stops short of enough to rebuild a
// short message, so the record answers "which item, of what kind, how much" and
// never "what did it say" — the same vocabulary the Pair Hint's refusal record
// uses, so the two can be cross-checked by digest.
const itemShapes = (items) => {
  const list = Array.isArray(items) ? items : [];
  return {
    count: list.length,
    shapes: list.slice(0, 64).map((item) => {
      const text = typeof item?.text === "string" ? item.text : "";
      return {
        kind: item?.kind ?? null,
        role: item?.role ?? null,
        member: typeof item?.member === "string" ? item.member : null,
        callId: typeof item?.callId === "string" ? item.callId : null,
        length: text.length,
        digest: createHash("sha256").update(text).digest("hex"),
      };
    }),
  };
};

export class SessionState {
  #ctx;
  #root;
  #sessionId;
  #registry;
  // Tool calls the gate refused, keyed by call id. `afterTool` still fires for a
  // skipped call, and Scope books on settle: without this the annotation of a
  // call nobody ran would still be spent (a refused `js-plan` wrote its
  // `resetScope` row on the live host). A skipped call is settled as refused.
  #skipped = new Set();
  #control;
  #parks;
  #views = new Map();
  #tenure;
  #workers;
  #fission;
  #extension = null;
  #sessions = null;
  #operator = null;
  #tools = [];
  #run = 0;
  #turn = 0;
  #prompted = false;
  #plan = null;
  #role;
  #identity = null;
  #decided = null;
  // `undefined` = no role fact written by this session; `null` = written; an
  // object = the write failed and says why (`roleFactWriteFailure`).
  #roleFactWrite = undefined;
  #model = null;
  #draftFile = null;
  #draftDir = null;
  #stateName;
  #asked = null;
  // A legal `S1` `ask` happened in the CURRENT turn (根 §2.2). It is the one
  // reason a user-facing session may end a turn while a body is still parked:
  // the question was put to the operator and the turn is theirs. `noteAsked`
  // sets it and `openTurn` clears it, so a later turn cannot inherit the exit.
  #askedInTurn = false;
  // A Plan's `review([])` hand-off was submitted (根 §2.2). The hand-off is the
  // second half of Plan's only legitimate stop: a successor must have verified
  // the previous tenure AND the draft must be on its way to a Manager.
  #handedOff = false;
  // The last nudge this session sent or tried to send: `{ role, reason, text,
  // delivered }`. It is the session's own record of a refused natural stop
  // (根 §2.2), and `null` until one happens.
  #nudge = null;
  // The run that a park ended, and whether its stop was already claimed
  // (根 §2.2 「中断」). `#parkStopOwed` is set when an `ask` parks, and cleared
  // when the stop is claimed or the run ends, so a later run never inherits it.
  #parkStopOwed = false;
  #parkStopped = false;

  constructor({ ctx = {}, operator = null, role = ROLES.MANAGER } = {}) {
    this.#ctx = ctx;
    this.#root = projectRoot(ctx);
    this.#sessionId = sessionIdOf(ctx);
    this.#stateName = SessionState.#nameFor(this.#sessionId);
    this.#operator = operator;
    this.#role = role;
    this.#registry = new ProgramRegistry();
    this.#control = new ControlSurface(this.#registry);
    this.#parks = new ParkTable();
    // Plan and Manager keep separate ledgers: a Plan advances through four
    // milestones and hands a draft off, a Manager through three and relays. The
    // file name carries the role so one project can hold both; a session whose
    // role is not known yet writes the Manager ledger until `adoptRole` says
    // otherwise, and adopts the other one then (the two never share a file).
    this.#tenure = this.#tenureFor(role === ROLES.PLAN ? "plan" : "manager");
    // The draft's directory is prepared here, where the root and the state name
    // are already known: the rule names the path on the very first request, and
    // the atomic write cannot create its directory. A directory that cannot be
    // made is kept as the reason the rule states, instead of surfacing later as
    // an ENOENT on a write that looked legal.
    this.#prepareDraftDir();
    // The roster is durable: a plugin loaded again into the same host process
    // must not answer `join` with "nothing to collect" for workers that are
    // still running (§4.1). The journal is the ONE source: the table replays
    // `.Herdchestra/<session>.workers.jsonl` as it is built (a torn last line is
    // a crash during append, not a record), so a mid-session reload and a cold
    // start read the same facts. There is no second snapshot to disagree with.
    this.#workers = new WorkerTable({
      create: (input) => this.startWorker(input),
      root: this.#root,
      sessionId: this.#stateName,
    });
    // Fission is one group per session (§4.2: no second group of one owner).
    // Its group record is committed to this session's own journal, beside the
    // ledger and the Scope book: one root derives every file.
    this.#fission = new SessionFission({
      workers: this.#workers,
      sessionId: this.#sessionId,
      groupJournal: () => path.join(this.#root, ".Herdchestra", `${this.#stateName}.fission.jsonl`),
    });
    // §4.2 steps 8 and 10: a lane's settled turn is its DELIVERY, and the
    // takeover lane's delivery is the announcement that ends the group. The
    // fission port registers the one listener it needs when it is first built
    // (`fission.mjs`), so a session that never fissions registers nothing.
  }

  // One ledger and one Scope journal per session and role. Both names carry the
  // state name, so two sessions that the host gave no id cannot collide.
  #tenureFor(kind) {
    return new TenureState({
      file: path.join(this.#root, ".Herdchestra", `${this.#stateName}.${kind}.json`),
      scopeFile: path.join(this.#root, ".Herdchestra", `${this.#stateName}.scope.jsonl`),
      sessionId: this.#sessionId,
      ctx: this.#ctx,
      kind,
    });
  }

  // The draft directory of this session, created once. `#draftFile` is set when
  // the role fact names the draft the entry derived; otherwise the path comes
  // from the session's own name, which is known at construction.
  #prepareDraftDir() {
    const file = this.#draftFile ?? planDraftPath(this.#root, this.#stateName);
    const prepared = ensureDraftDir({ root: this.#root, file });
    this.#draftDir = prepared.ok === true ? null : prepared;
    return this.#draftDir;
  }

  // Why this session's draft directory could not be prepared, or `null`. The
  // rule states the reason, because the alternative is a write that fails for a
  // cause the model cannot see.
  get planDraftDirFailure() {
    return this.#draftDir === null ? null : { ...this.#draftDir };
  }

  get ctx() {
    return this.#ctx;
  }

  // Install the operator seam. `index.mjs` builds the session first so the
  // seam's prompt channel can record a question on it, and hands the seam back
  // here; a port reads it lazily, so this must happen before the first `ask`.
  setOperator(operator) {
    this.#operator = operator ?? null;
    return this;
  }

  get root() {
    return this.#root;
  }

  get sessionId() {
    return this.#sessionId;
  }

  // The model this session actually runs: the provider and model of the last
  // request the host made. The plugin's setup context does not carry them, and
  // a hand-off that reused a guessed provider would start the successor on the
  // wrong connection. `null` until a reading exists, and never a guess.
  //
  // A Plan hand-off (and the session's own top-level model) needs this BEFORE
  // the session's own first request has been made. So when no request has been
  // seen, the reading falls back, in order of proof:
  //
  //   1. the setup context (`ctx.model`, `ctx.config.providerId/modelId`,
  //      `ctx.session.provider/model`) — a host that hands the connection over;
  //   2. `CLINE_PROVIDER_ID` / `CLINE_MODEL_ID`, for an embedder that pins them;
  //   3. the host's own session record (`getSession`), whose row and metadata
  //      carry `provider` and `model` on Cline 0.2.0 (measured on a live
  //      Desktop row). This one is a promise, so only `resolveModel` can reach
  //      it — which is why every start path uses `resolveModel`, not `model`.
  //
  // Every source names BOTH halves or answers nothing: a provider without a
  // model would start a runtime that cannot connect. `modelSource` says which
  // one answered, so a receipt can explain itself.
  get model() {
    if (this.#model !== null) return { ...this.#model };
    const fromContext = modelFromContext(this.#ctx);
    if (fromContext !== null) return fromContext;
    const fromEnv = modelFromEnvironment();
    if (fromEnv !== null) return fromEnv;
    return null;
  }

  // Which reading produced `model`: `request`, `context`, `environment`, or
  // `null` when nothing answered. The host record is resolved asynchronously
  // (`resolveModel`), because `getSession` is a promise.
  get modelSource() {
    if (this.#model !== null) return "request";
    if (modelFromContext(this.#ctx) !== null) return "context";
    if (modelFromEnvironment() !== null) return "environment";
    return null;
  }

  // The connection, with the host's own session record tried last. This is the
  // reading `fork`/`startManager`/`devops` use, because they may run before any
  // model turn of this session.
  //
  // `host` is injectable so a caller that already holds a handle (a test, or a
  // caller that opened one for another reason) does not open a second. With no
  // argument the session's own factory supplies it, which is the live path.
  async resolveModel(host = null) {
    // The order here IS the documented ladder: request, context, environment,
    // then the host record (the only asynchronous step). `model` answers the
    // first three synchronously, so a session with any of them never waits on
    // a host round trip.
    const known = this.model;
    if (known !== null) return { model: known, source: this.modelSource };
    const opened = host ?? (await this.#sessionsForPlan().host());
    const record = await this.#sessionRecord(opened);
    const fromRecord = modelFromRecord(record);
    if (fromRecord !== null) return { model: fromRecord, source: "sessionRecord" };
    return { model: null, source: null };
  }

  // The connection a delegated runtime must be started with: the model ladder
  // above, PLUS the credentials the host already stores for that provider.
  //
  // The model alone is not a connection. `createDelegatedAgentConfigProvider`
  // forwards exactly what it is given, so a runtime built with `{ providerId,
  // modelId }` reaches the provider and dies on "You didn't provide an API key"
  // — measured against the live Desktop install. `providerCredentials` asks the
  // host's own `ProviderSettingsManager`, which already holds the key and the
  // base URL for the session's provider.
  //
  // `credentials` is `null` when the host has none (an embedder with its own
  // config, or an older SDK). The runtime is still started, and the provider's
  // own error is the honest report; `credentialSource` says which case it was.
  async resolveConnection(host = null) {
    const resolved = await this.resolveModel(host);
    if (resolved.model === null) return { ...resolved, credentials: null, credentialSource: null };
    const credentials = await providerCredentials({
      providerId: resolved.model.providerId,
      modelId: resolved.model.modelId,
    });
    return {
      model: resolved.model,
      source: resolved.source,
      credentials,
      credentialSource: credentials === null ? "hostHasNone" : "providerSettings",
    };
  }

  // The connection a SUBAGENT runs on (root §1.1 item 6): the task ladder when
  // the operator pinned one, the main ladder otherwise.
  //
  // The task ladder is the environment pair `HERDCHESTRA_TASK_PROVIDER_ID` /
  // `HERDCHESTRA_TASK_MODEL_ID`; both halves are required. It sits BEFORE the
  // main ladder, and its `source` is `"task"`. A session that pinned nothing
  // falls back to the main ladder and reports `"main"` — the receipt names
  // which ladder answered, so a worker started on the main model is visible as
  // such instead of looking like a task-model start.
  //
  // Manager and Plan top-level sessions do NOT use this: they are the main
  // conversation, and their model is the session's own (`resolveModel`).
  async resolveTaskModel(host = null) {
    const pinned = modelFromTaskEnvironment();
    if (pinned !== null) return { model: pinned, source: "task" };
    const main = await this.resolveModel(host);
    return { model: main.model, source: main.model === null ? null : "main" };
  }

  // `resolveTaskModel` plus the credentials the task provider needs. The
  // credential reader is the same one the main ladder uses: the host stores one
  // key per provider, not one per model.
  async resolveTaskConnection(host = null) {
    const pinned = modelFromTaskEnvironment();
    if (pinned === null) {
      const main = await this.resolveConnection(host);
      return { ...main, source: main.model === null ? null : "main" };
    }
    const credentials = await providerCredentials({
      providerId: pinned.providerId,
      modelId: pinned.modelId,
    });
    return {
      model: pinned,
      source: "task",
      credentials,
      credentialSource: credentials === null ? "hostHasNone" : "providerSettings",
    };
  }

  // The host's record of this session, or null. Never throws: a host that
  // cannot answer is the same as one that answered nothing.
  async #sessionRecord(host) {
    if (host === null || host === undefined) return null;
    const handle = host.handle ?? host;
    const reader =
      typeof handle?.getSession === "function"
        ? (id) => handle.getSession(id)
        : typeof handle?.get === "function"
          ? (id) => handle.get(id)
          : null;
    if (reader === null) return null;
    try {
      const record = await reader(this.#sessionId);
      if (record !== null && record !== undefined) return record;
      return await reader({ sessionId: this.#sessionId });
    } catch {
      return null;
    }
  }

  // Record the connection of one model request. The host names the provider and
  // model on every request it makes (`AgentModelRequest` after the runtime adds
  // the connection), so this is a reading, not an inference.
  noteModel(request) {
    if (request === null || typeof request !== "object") return this.#model;
    const providerId = typeof request.providerId === "string" ? request.providerId : request.provider;
    const modelId = typeof request.modelId === "string" ? request.modelId : request.model;
    if (typeof providerId !== "string" || providerId.trim().length === 0) return this.#model;
    if (typeof modelId !== "string" || modelId.trim().length === 0) return this.#model;
    this.#model = { providerId, modelId };
    return this.#model;
  }

  // The name the state files use. `setup` carries the session id when the host
  // has one; a missing id must not let two sessions of one project share one
  // ledger, so an id-less session gets a name of its own.
  static #unnamed = 0;

  static #nameFor(sessionId) {
    if (typeof sessionId === "string" && sessionId.length > 0 && sessionId !== "cline-session") {
      return safeName(sessionId);
    }
    SessionState.#unnamed += 1;
    return `${safeName(sessionId ?? "cline-session")}-${SessionState.#unnamed}`;
  }

  // The role this session plays, as the host records it (§7.2). It is a fact of
  // the session, not a constant of the plugin load: a session this adapter
  // started carries its role in its metadata, and the plugin's own setup reads it
  // back. `role` passed to the constructor is the role of a session nobody
  // marked (a test, or an operator's own session).
  get declaredRole() {
    return this.#role;
  }

  // How the role was decided, once it has been: `{ role, source, metadata }`.
  // `null` before the first resolve. The `source` is the diagnostic — it names
  // WHICH reading produced the role, so "the session looks like a Manager" can
  // be told apart from "the host could not be asked" from outside the process.
  get identity() {
    return this.#identity === null ? null : this.#decided;
  }

  async resolveRole() {
    if (this.#identity !== null) return await this.#identity;
    this.#identity = (async () => {
      // A host that already handed the metadata to `setup` answers at once: no
      // session record is read and no host handle is opened for it.
      const given = this.#ctx?.sessionMetadata;
      const handed = given !== null && typeof given === "object";
      // The entry that started this session recorded its role beside our other
      // facts (`role-fact.mjs`), keyed by the session id the start request
      // carried. That file is the ONE channel that survives the plugin sandbox,
      // where the host cannot be asked at all: the plugin is set up before the
      // session is registered, and a sandboxed plugin cannot resolve
      // `@cline/core` anyway. It is read first for that reason, and it is a fact
      // the entry wrote before the start, not a guess.
      // The fact may sit under the project root or the workspace the host
      // reports: this adapter derives `#root` from the workspace, while the
      // entry that wrote the fact used the `root` it was asked to plan in. Both
      // are real candidates, so both are read before the host is asked.
      const recorded = handed
        ? null
        : (readRoleFact({ root: this.#root, sessionId: this.#sessionId }) ??
          readRoleFact({ root: workspaceRootOf(this.#ctx) ?? this.#root, sessionId: this.#sessionId }));
      const host = handed || recorded !== null ? null : await this.#sessionsForPlan().host();
      const decided =
        recorded === null
          ? await roleOf({
              host,
              sessionId: this.#sessionId,
              metadata: handed ? given : null,
              fallback: this.#role,
            })
          : { role: recorded.role, source: "roleFact", metadata: recorded.draft === undefined ? null : { "herdchestra.draft": recorded.draft } };
      this.#decided = decided;
      this.adoptRole(decided.role);
      // An `unproven` role is NOT recorded here. Every source failed, so the
      // fallback (`#role`) is a default, not a reading — and writing it would
      // make the next load believe a default was a fact. Worse, it would freeze
      // the role before the only evidence exists: an operator's first call can
      // be `js-plan`, which makes the session Plan, not Manager. The record is
      // written when the host snapshot decides (`recordOperatorRole`, called
      // from the gate), which is the first moment the role IS a reading.
      // A session started by `startPlan` names its draft in the same metadata;
      // the ledger and the `js-plan` rule must read the path the entry derived.
      const draft = decided.metadata?.["herdchestra.draft"];
      if (typeof draft === "string" && draft.length > 0) this.#draftFile = draft;
      // A role that arrived with a draft path prepares that directory too: the
      // rule names it, so it must exist by then.
      this.#prepareDraftDir();
      return decided;
    })();
    return await this.#identity;
  }

  // Write this session's role fact, once. The role written is the declared one:
  // `adoptRole` already decided which ledger this session books on, and a file
  // that disagreed would make the next load adopt a different one.
  //
  // A refused write is not an error: the snapshot path decides every call on
  // its own, and refusing a session over an unwritable workspace would turn a
  // diagnostic into an outage. The reason is kept for the trace.
  #recordOperatorRole() {
    if (this.#roleFactWrite !== undefined) return this.#roleFactWrite;
    const written = writeRoleFact({ root: this.#root, sessionId: this.#sessionId, role: this.#role });
    this.#roleFactWrite = written.ok === true ? null : { reason: written.reason, detail: written.detail ?? null };
    return this.#roleFactWrite;
  }

  // The gate established this session's role from the operator's own snapshot
  // (`hooks.mjs`). Adopt it — the ledger and the prompt follow the role — and
  // record it, so the role survives a reload and reaches the hooks that carry
  // no snapshot. Returns the write receipt, or the failure already recorded.
  recordOperatorRole(role) {
    if (typeof role === "string" && MARKABLE_ROLES.has(role) && role !== this.#role) this.adoptRole(role);
    // The identity is no longer a question once the snapshot answered it: the
    // source says WHICH reading decided, and `sessionSnapshot` is the snapshot
    // of the operator's own call. `unproven` would keep refusing every face
    // after the very call that proved the role.
    this.#decided = { role: this.#role, source: "sessionSnapshot", metadata: this.#decided?.metadata ?? null };
    const failed = this.#recordOperatorRole();
    return failed === null ? { ok: true } : { ok: false, ...failed };
  }

  // Why the role fact could not be written, or `null` when it was (or has not
  // been attempted). A diagnostic: no decision reads it.
  get roleFactWriteFailure() {
    return this.#roleFactWrite ?? null;
  }

  // Switch to the role the host recorded. A session marked as a Plan gets the
  // Plan ledger (four milestones, its own file); anything else keeps the Manager
  // one. The switch happens before the first hook, so no review is ever booked
  // against the wrong ledger.
  adoptRole(role) {
    if (role === this.#role) return this.#role;
    // No ledger is ever opened on a role this adapter does not name: the tenure
    // file and the Scope journal would be written under a guess.
    if (!MARKABLE_ROLES.has(role)) {
      throw Object.assign(new Error(`unknown session role: ${String(role)}`), { code: "UNKNOWN_SESSION_ROLE" });
    }
    this.#role = role;
    this.#tenure = this.#tenureFor(role === ROLES.PLAN ? "plan" : "manager");
    return this.#role;
  }

  get registry() {
    return this.#registry;
  }

  get control() {
    return this.#control;
  }

  get parks() {
    return this.#parks;
  }

  get views() {
    return this.#views;
  }

  get tenure() {
    return this.#tenure;
  }

  get workers() {
    return this.#workers;
  }

  // The host is ending this session (`PluginSetupContext.onDispose`). Every
  // delegated runtime this session started is stopped here: a worker is a real
  // session in the host's process, and a Hub host shares that process across
  // sessions, so a worker left alive would keep producing turns for a session
  // nobody is listening to (根 §7.2). A question still waiting for the operator
  // is dropped with it — there is no one left to answer it.
  //
  // The receipt is the worker table's (`{ ok, released, orphaned }`), because it
  // is the same fact `abortLanes` reports: an abort that threw leaves a runtime
  // that may still run, and saying "stopped" would be a lie.
  release() {
    this.#asked = null;
    // The lane listener reports to a session that is over; it goes with it.
    this.#fission.stop();
    // Plan's dedicated DevOps is not in the worker table; it stops with the
    // session like any other runtime this adapter started.
    if (this.#plan !== null) this.#plan.devops.cancel("SESSION_ENDED");
    const receipt = this.#workers.abortAll();
    // The journal is already on disk: `save` answers where the next load will
    // read the roster from, and a journal that cannot be named is reported
    // beside the teardown receipt rather than dropped.
    return { ...receipt, roster: this.#workers.save() };
  }

  // Root §1.1 item 2: an operator interrupt at a user-facing session must reach
  // every descendant, or the cancelled work keeps spending the operator's
  // tokens in the background. This is NOT teardown (`release`): the session
  // lives on, so unclaimed completions stay collectable, and no runtime is
  // disposed beyond the abort the cancellation asks for.
  //
  // The worker table holds every descendant — forked workers and fission lanes
  // alike — because a worker's own `fork` goes through this same session.
  cancelDescendants(reason = "OPERATOR_CANCELLED") {
    const receipt = this.#workers.cancelAll(reason);
    // Plan's dedicated DevOps is a descendant too, and it is not in the worker
    // table: the session holds its one handle directly. A runner that never
    // built a handle answers `cancelled: false`; that is not a failure.
    const devops = this.#plan === null ? null : this.#plan.devops.cancel(reason);
    return devops === null ? receipt : { ...receipt, devops };
  }

  // The factory this session starts other sessions with (a Plan entry, a
  // hand-off). It opens the host handle lazily and answers the same object every
  // time, so a Plan and the Manager it hands off to share one host connection.
  get sessions() {
    return this.#sessionsForPlan();
  }

  get clock() {
    return clock;
  }

  get turn() {
    return this.#turn;
  }

  get run() {
    return this.#run;
  }

  // The plugin's own extension object, forwarded unchanged into a Manager
  // session this adapter starts (spec §6.1), and into every delegated runtime.
  setExtension(extension) {
    this.#extension = extension;
    // The host handle a new session is started on. The new session loads the
    // plugin itself (`config.pluginPaths` — the field the session loader reads;
    // `agentPluginPaths` is the package loader's and loads nothing), so the
    // extension object is not passed to it: forwarding a live module object into
    // another session's setup would hand two sessions one adapter, and the role
    // read-back would then describe the wrong session.
    // The factory starts sessions on THIS session's connection. Setup carries no
    // provider or model, so the getter hands over the reading taken from the
    // session's own model requests (`model`), and the awaited form also consults
    // the host's session record — never a default.
    const session = this;
    this.#sessions = new ManagerSessions({
      ctx: this.#ctx,
      cwd: this.#root,
      model: () => session.model,
      resolveModel: () => session.resolveModel(),
      // The same provider-settings reader `fork` uses: a handed-off session
      // without credentials reaches the provider and dies there.
      resolveCredentials: (connection) => providerCredentials({ providerId: connection.providerId, modelId: connection.modelId }),
    });
    return this;
  }

  // The tools a delegated runtime receives. The host's subagent factory takes
  // the list from its caller, so a worker gets the Herdchestra face and nothing
  // of the host's builtin set (spec §7.2).
  noteTools(tools) {
    this.#tools = [...tools];
    return this;
  }

  workerTools() {
    return [...this.#tools];
  }

  // The role of the agent making a call. A child this adapter started is its
  // recorded role; any other child is a coder, because coder and devops share one
  // surface (spec §5) and differ only in their verboseIntent. A top-level caller is the
  // Manager; whether it may use a worker face is `hooks.beforeTool`'s decision.
  //
  // The test is the VALUE, never the key. `parentAgentId` is optional on the
  // host's snapshot (`@cline/shared` `AgentRuntimeStateSnapshot.parentAgentId?`),
  // and the live `@cline/core` 0.2.0 runtime omits it for a top-level agent
  // (observed keys: agentId, conversationId, runId, status, iteration, messages,
  // pendingToolCalls, usage). Testing `"parentAgentId" in snapshot` therefore
  // read a top-level Manager as a coder and refused it its own face — the live
  // trace was `{tool: "js-manager", role: "coder", declaredRole: "manager"}`.
  // An absent key and a `null`/`undefined` value are one fact: no parent.
  roleFor(context) {
    const snapshot = context?.snapshot;
    if (snapshot === null || typeof snapshot !== "object") return ROLES.CODER;
    const parent = snapshot.parentAgentId;
    if (parent === null || parent === undefined) return ROLES.MANAGER;
    const worker = this.#workers.get(snapshot.agentId);
    return worker?.role === ROLES.DEVOPS ? ROLES.DEVOPS : ROLES.CODER;
  }

  // Ports compose reads. `fork`/`join`/`relay`/`command`/`fission` are real;
  // `control` resumes a park through core's checks, after the caller's request
  // observed the target (spec §3.3).
  ports(role) {
    const ports = {
      command: commandPort({ cwd: this.#root }),
      control: {
        // The surface passes the controller's own capability set and the
        // controlling request's deadline (compose `surface.mjs`); core checks
        // that set against the *target* program's own set, so dropping it here
        // would silently downgrade the check to "no capabilities required".
        unpark: ({ controllerId, name, value, capabilities = [], targetCapabilities = null, deadline = null }) => {
          // The continuation is awaited, not merely started: the controller's
          // `await this.unpark(name, value)` must answer the resumed program's
          // own receipt, and a body that has to run before it can answer is a
          // promise.
          //
          // Core cannot build that receipt — it never sees the body return — and
          // it spreads whatever the hook answers, so an un-awaited promise would
          // reach the controller as `{}`. The hook therefore only WAKES the
          // program (`#parks.settle`), and the continuation is awaited here.
          const receipt = this.#control.unpark({
            controllerId,
            name,
            value,
            capabilities,
            targetCapabilities,
            deadline,
            // Wake only. Core's own view is what it returns; this port replaces
            // it with the continuation's answer below.
            settle: (resumeValue) => this.#parks.settle(name, resumeValue),
          });
          // `undefined` from the hook means nothing was parked: core already
          // refused or the program had moved on, and its own answer stands.
          if (receipt === null || typeof receipt !== "object") return receipt;
          // The wake-up already happened inside `#control.unpark` (that is what
          // `settle` is for), so this only AWAITS the continuation. Calling
          // `ParkTable.resume` here would settle a second time — the entry's
          // `resolve` is gone by then, so it would answer `false` and the
          // controller would read core's `running` view instead of the body's
          // real outcome.
          return this.#awaitContinuation(name, value, receipt);
        },
      },
    };
    if (role === ROLES.PLAN) {
      // Plan (根 §2.2): `cat`/`write` come from the surface, the other three
      // members are ports. `devops` runs one investigation through the same
      // delegated factory `fork` uses; `draft` reads the canonical draft file so
      // the ledger can refuse an empty hand-off before a Manager exists.
      const plan = this.#planState();
      return {
        ...ports,
        ...planPorts({
          file: plan.file,
          devops: { investigate: (verboseIntent) => plan.devops.investigate(verboseIntent) },
          // A Plan and a Manager each ask for their own relay, and the port
          // records the hand-off as it performs it (`#relayPort`).
          relay: this.#relayPort(),
        }),
      };
    }
    // `fork`/`join` belong to every role that may dispatch (§3.1, §4.1): the
    // Manager and every worker. Plan never gets them (§2.2, §6). The role is
    // the one compose resolved from the verboseIntent — never a default here, because a
    // port that filled in "coder" would silently downgrade a devops request.
    ports.fork = {
      fork: (name, verboseIntent, forkRole) =>
        this.#workers.fork({ name, verboseIntent, role: forkRole }),
      join: () => this.#workers.join(),
    };
    // The two exempt snapshot queries (§6.2). `ScopeHooks#ask` is synchronous
    // and answers from the newest committed facts, so the port hands the answer
    // straight back; a refusal (a book mid-settle, an unknown member) travels as
    // the value it is.
    ports.scope = {
      ask: (member) => this.#tenure.scope().ask(member, this.#sessionId),
    };
    if (role === ROLES.MANAGER) {
      ports.relay = this.#relayPort();
    } else {
      ports.fission = this.#fission.port();
    }
    return ports;
  }

  // The session's relay port, with the one fact this session must record
  // attached to it.
  //
  // 根 §2.2: a Plan's `review([])` at 「待执行」 is its hand-off, and a hand-off
  // that really started a Manager is the second half of Plan's only legitimate
  // stop. The surface's `review` member reaches `ReviewMember` ->
  // `relayPort.startManager` directly and never `reviewFor`, so the PORT is the
  // one place both faces meet. Recording here is what lets
  // `guard.verifiedCompletion` see `handedOff: true`; without it a Plan that
  // handed off was refused its earned stop and nudged into starting a second
  // Manager.
  #relayPort() {
    const relay = relayPort({ tenure: this.#tenure, sessions: this.#sessions, operator: this.#operator });
    const session = this;
    return {
      ...relay,
      async startManager(draft) {
        const result = await relay.startManager(draft);
        session.noteHandoff(result);
        return result;
      },
    };
  }

  // One Scope projection that refused leaves one durable line in the session's
  // request journal. §6.2/§6.3 require the diagnosis, and a refusal that is only
  // visible in a returned value is invisible in a live host: the request still
  // goes out with the CUE/barrier copy, and nothing said why the projection did
  // not apply.
  #recordScopeRefusal(refusal, items) {
    const file = path.join(this.#root, ".Herdchestra", `${this.#stateName}.requests.jsonl`);
    try {
      appendJsonLine(file, {
        v: 1,
        at: new Date(clock.now()).toISOString(),
        kind: "scopeProjectionRefused",
        sessionId: this.#sessionId,
        code: typeof refusal?.code === "string" ? refusal.code : "SCOPE_PROJECTION_REFUSED",
        message: typeof refusal?.message === "string" ? refusal.message : null,
        items: itemShapes(items),
      });
    } catch {
      // A diagnostic that cannot be stored is lost; the request it describes is
      // not. The same rule the Pair Hint refusal record follows.
    }
  }

  // The continuation of a program whose park was already woken, as the receipt a
  // controller reads.
  //
  // The body of a parked program is still awaiting the promise the park table
  // held, so waking that promise IS the continuation (根 §3.3): the expression
  // `await this.ask(...)`, `await this.where(...)` or `await this.park(...)`
  // evaluates to `value`, and every statement after it runs with the locals it
  // already computed. `ParkTable.continuation` races it against the program's
  // next park, so this answers the resumed program's own receipt: its terminal
  // one when the body returned, or its parked view at the NEW site when it
  // parked again.
  //
  // `fallback` is core's own view of the resume. It is used only when there is
  // no surface to describe the continuation (a park this adapter does not own),
  // so a host that parked through its own channel still gets an answer.
  async #awaitContinuation(name, value, fallback) {
    const entry = this.#parks.entries.get(name) ?? null;
    // The question is answered by the resume itself, whichever route woke it
    // (根 §3.3 example 7: the AI's `this.unpark(name, answer)` is as legitimate
    // as the operator's own message). A record left behind would make the NEXT
    // operator message resume a program that already continued.
    const answeredAsk = this.#asked !== null && this.#asked.name === name;
    if (answeredAsk) this.#asked = null;
    const outcome = await this.#parks.continuation(name);
    if (outcome === false) return fallback;
    // The call's own `run` answered at the park and will never see this body
    // end, so the program is finished HERE: otherwise a resumed program stays
    // `running` forever, its name is never retired, and its receipt describes a
    // program that has already returned.
    //
    // This runs BEFORE the receipt is built, because `finalReceipt` reads the
    // program's state and result at call time: a receipt built first would
    // describe a program that has not finished yet. The facts the receipt still
    // needs from the entry — `surface` and `resolvedSite` — are captured on the
    // entry object, which `close` does not touch.
    if (entry?.call !== null && entry?.call !== undefined && typeof entry.call.settleContinuation === "function") {
      entry.call.settleContinuation(outcome);
      if (outcome?.status !== "parked") {
        this.#tenure.noteFinished(name);
        this.#parks.close(name);
      }
    }
    const receipt = this.#continuationReceipt(name, entry, outcome, value, fallback);
    // A body that parked AGAIN on a new question has already recorded that new
    // one (`#continuationReceipt`), and it must survive.
    if (answeredAsk && receipt?.state === "parked" && this.#asked === null && entry?.surface !== null) {
      const site = this.#parks.siteOf(name);
      if (site?.reason === "ask") {
        this.#recordAsk({
          name,
          question: site?.payload?.question ?? null,
          resume: (answer) => void this.resume(name, answer),
          surface: entry.surface,
        });
      }
    }
    return receipt;
  }

  // What a resumed body answered, as the receipt a controller reads.
  //
  // A body that parked again did not finish: its receipt is the parked view of
  // the SAME program at the new site, with the value its previous expression
  // received. A body that returned answers its terminal receipt. A park with no
  // surface is not a program body this adapter runs (a host that parked through
  // its own channel), and then the resume itself is the whole answer.
  #continuationReceipt(name, entry, outcome, value, fallback) {
    const surface = entry?.surface ?? null;
    if (surface === null || typeof surface.receipt !== "function") {
      return fallback;
    }
    const extra = value === undefined ? {} : { value };
    if (outcome?.status === "parked") {
      // The body parked AGAIN (a second `ask`, a `where` ambiguity, an explicit
      // park). A resumed `ask` that asks a new question must reach the operator
      // the same way the first one did, or the new question would sit in the
      // park table with nobody told about it.
      if (outcome.site?.reason === "ask") {
        const site = outcome.site;
        this.#recordAsk({
          name,
          question: site?.payload?.question ?? null,
          resume: (answer) => void this.resume(name, answer),
          surface,
        });
      }
      return surface.receipt({ reason: outcome.site?.reason ?? "explicit", payload: outcome.site?.payload ?? null, ...extra });
    }
    // The body returned or threw. Its terminal receipt carries the program's
    // result, and it also names the site the controller RESOLVED — the parked
    // program no longer holds one, so a receipt built from the program alone
    // would report `reason: null` for a resume that had a reason (根 §3.3: the
    // receipt describes the park the controller observed).
    const site = entry?.resolvedSite ?? entry?.site ?? this.#parks.siteOf(name);
    return surface.finalReceipt({
      reason: site?.reason ?? null,
      payload: site?.payload ?? null,
      ...extra,
    });
  }

  // One devops runner per session, created lazily so a session that never plans
  // does not build one (spec §6: at most one unresolved devops per task).
  //
  // The draft path is derived from the session: the plugin's own session when the
  // host gave it a session (its `setup` context always carries one), otherwise
  // the name this session was started under. Two plan sessions of one project
  // never share a draft.
  #planState() {
    if (this.#plan === null) {
      // The devops runner starts its runtime with the worker face this adapter
      // registers. The tool objects exist as soon as the plugin is registered,
      // and the runner reads them at the call, not here, so a devops started
      // after registration always carries `js-worker`.
      //
      // The getter must close over the session: `createDevopsRunner` destructures
      // its options, so a getter would be read with `this` bound to the options
      // object, which has no `workerTools`.
      const session = this;
      const devops = createDevopsRunner({
        ctx: this.#ctx,
        cwd: this.#root,
        extension: this.#extension,
        // The devops runtime is a SUBAGENT: it runs on the task ladder when the
        // operator pinned one (root §1.1 item 6), and on the main ladder
        // otherwise. Credentials travel with it, or the runtime dies at the
        // provider on "no API key".
        resolveConnection: () => session.resolveTaskConnection(),
        get tools() {
          return session.workerTools();
        },
      });
      // The draft is named by `#stateName`, the same name the constructor used
      // to prepare its directory and the same one the ledger files carry. A
      // session the host gave no id gets a sequence suffix there; deriving the
      // name a second way here would prepare one directory and name another.
      const name = this.#stateName;
      // A Plan session started by `startPlan` carries the draft path the entry
      // derived (`#draftFile`, read with the role); any other session derives it
      // from its own name. Both come from one function, so the rule, the ledger
      // and the write cannot disagree.
      const file = this.#draftFile ?? planDraftPath(this.#root, name);
      this.#plan = { devops, file, name, read: () => readDraft(file) };
    }
    return this.#plan;
  }

  // The session factory a Plan entry uses. It is the same `ManagerSessions` a
  // hand-off uses: both start sessions on the host's own factory, and neither
  // reuses this session's ledger.
  #sessionsForPlan() {
    if (this.#sessions === null) this.setExtension(this.#extension);
    return this.#sessions;
  }

  get planDraftFile() {
    return this.#planState().file;
  }

  surface({ role, name, program, park, source = null }) {
    const surface = new ProgramSurface({
      name,
      role,
      program,
      // 根 §3.3: the program text is part of the receipt, so the caller hands
      // it in with the program it belongs to.
      source,
      clock,
      ports: this.ports(role),
      root: this.#root,
      park,
      views: this.#views,
      ledger: this.#tenure.ledger,
      // The ledger outlives one program: `review` advances a milestone, and the
      // Plan's draft stays read-only until the tenure's first review. Without a
      // store, a review accepted inside one program was never written down — the
      // live Plan showed two accepted reviews and no `.plan.json` on disk.
      ledgerStore: this.#tenure,
      // Root §2.3: the evaluation window belongs to the tenure, so a review
      // accepted by one program keeps `js-review` shut for the next one.
      window: this.#tenure.window,
    });
    return { surface, members: Object.freeze(surface.surface()) };
  }

  // The separate read-only surface of the evaluation phase (根 §2.3). A shut
  // window makes `reviewSurface()` throw `REVIEW_WINDOW_CLOSED`; the refusal
  // reaches the caller unchanged, so the tool answers the reason.
  reviewSurface({ name, program, park, source = null }) {
    const surface = new ProgramSurface({
      name,
      role: ROLES.MANAGER,
      program,
      source,
      clock,
      ports: {},
      root: this.#root,
      park,
      views: this.#views,
      window: this.#tenure.window,
    });
    // The tenure owns the request boundary (§2.3), so the host states it: the
    // call that reached here is the Manager's request of its round.
    return { surface, members: Object.freeze(surface.reviewSurface({ openRound: true })) };
  }

  // The compose `review` entry point for this tenure (spec §5). `role` decides
  // which ports the member sees: Plan reads its draft before a hand-off, the
  // Manager never does.
  reviewMember(role = ROLES.MANAGER) {
    // No window here (根 §2.3): the surface books the receipt, because every
    // face of a review goes through it. One owner for the booking.
    return new ReviewMember({
      ledger: this.#tenure.ledger,
      port: this.ports(role).relay,
    });
  }

  // ---------------------------------------------------------------------
  // Hooks
  // ---------------------------------------------------------------------

  /**
   * A run starts. This is *not* a Scope turn: one run holds many model
   * requests, and Scope books per model turn (`iteration` in the host's
   * `AgentBeforeModelContext.snapshot`).
   */
  beginRun() {
    this.#run += 1;
    this.#tenure.nextTurn();
    // A new run owes no stop from an earlier one: the park that ended the last
    // run was answered or abandoned, and its stop belongs to that run.
    this.clearParkStop();
    return this.#run;
  }

  /**
   * One model turn starts. The Scope book opens a turn lazily from the first
   * `settle` that carries a new `turnId`, so this only advances the id: a
   * `meToo` staged in one iteration cannot be carried into the next.
   */
  openTurn() {
    this.#turn += 1;
    // A new turn has asked nothing yet: the `S1` exit belongs to the turn that
    // put the question to the operator (根 §2.2).
    this.#askedInTurn = false;
    return this.#turn;
  }

  /**
   * One model turn ends. A turn that only staged `meToo` is refused here and
   * the refusal is returned as a value, not thrown: the host must still be able
   * to answer the request that produced it.
   */
  closeTurn() {
    return this.#tenure.scope().closeTurn({ sessionId: this.#sessionId });
  }

  // ---------------------------------------------------------------------
  // ask: the operator's answer resumes the program that asked
  // ---------------------------------------------------------------------

  /**
   * A program parked on `ask(question)`. The call stays open in the park table
   * and `resume(answer)` settles it, so the body continues from the parked
   * expression (根 §2.2: the attempt is interrupted, the answer arrives as a
   * real message, the same task continues — no new tenure, no review).
   *
   * The same fact ends the turn: a legal `S1` `ask` is one of the two exits a
   * user-facing session may stop at, so the turn is marked as having asked
   * (`askedInTurn`). Only compose's `ask` member reaches this, and that member
   * refuses every call outside Plan's `S1` (`ASK_FORBIDDEN_OUTSIDE_S1`), so the
   * mark is never set by a refused call.
   */
  noteAsk({ name, question, resume, surface = null }) {
    return this.#recordAsk({ name, question, resume, surface });
  }

  // One `ask` is one record AND the turn's interrupt (根 §2.2: `this.ask` IS
  // park + interrupt). The three facts move together — the question, the S1
  // exit, and the park stop this run owes — because a continuation that asks
  // again parks a SECOND question that must interrupt its run exactly like the
  // first. Recording only `#asked` left the second question parked while the
  // run carried on and was nudged.
  #recordAsk({ name, question, resume, surface = null }) {
    this.#asked = { name, question, resume, surface };
    this.#askedInTurn = true;
    // The interrupt is a real stop of THIS run, not only a receipt field. The
    // turn that parked must have no further model step; `guard.parkStop` is what
    // asks the host for that, and this flag is the obligation it answers.
    this.#parkStopOwed = true;
    this.#parkStopped = false;
    return this.#asked;
  }

  get asked() {
    return this.#asked === null ? null : { name: this.#asked.name, question: this.#asked.question };
  }

  // Whether a legal `S1` `ask` happened in this turn. The turn boundary
  // (`openTurn`) clears it, so the flag always describes the turn that is
  // ending, never one before it.
  get askedInTurn() {
    return this.#askedInTurn;
  }

  // Whether the run of a parked `ask` still owes its stop.
  get parkStopOwed() {
    return this.#parkStopOwed;
  }

  // Whether this session's parked run was actually stopped by the host.
  get parkStopped() {
    return this.#parkStopped;
  }

  // Claim the one stop a parked `ask` owes. `true` exactly once per park: the
  // host may consult several hooks for the same turn, and the second claim is
  // the same turn asking again, not a second stop.
  claimParkStop() {
    if (this.#parkStopOwed !== true || this.#parkStopped === true) return false;
    this.#parkStopOwed = false;
    this.#parkStopped = true;
    return true;
  }

  // The run that carried the park is over. Nothing is owed any more, and the
  // next run starts with a clean obligation.
  clearParkStop() {
    this.#parkStopOwed = false;
    this.#parkStopped = false;
  }

  // Whether this Plan has submitted its `review([])` hand-off. It is a fact of
  // the session, not of one turn: the hand-off starts a Manager and the Plan's
  // work is done, so a later turn may stop without submitting another.
  get handedOff() {
    return this.#handedOff;
  }

  /**
   * Resume a named parked program with a value, from outside a program (根 §2.2,
   * §3.3). This is the other half of `ask`: the operator's answer may be
   * injected by the AI's own `this.unpark(name, answer)`, and this is the same
   * injection made by the host when the answer arrives as a message.
   *
   * This is the same continuation `this.unpark` performs (`settle` then
   * `continuation`), so an answer that arrives as the operator's message and one
   * that arrives as the AI's own `this.unpark(name, answer)` continue the body
   * identically.
   *
   * The park table is the judge: a name that is not parked answers `false`, so
   * a stale name never resumes a second time. Otherwise the answer is the
   * continuation's receipt: the resumed program's terminal receipt, or its next
   * parked receipt when the body parked again.
   */
  async resume(name, value) {
    if (typeof name !== "string" || name.length === 0) return false;
    const parked = this.#parks.siteOf(name) !== null;
    if (!parked) return false;
    const entry = this.#parks.entries.get(name) ?? null;
    // The program leaves `parked` for `running` exactly once, as core's own
    // `unpark` does (`ProgramState.takeSite`). Without this step a resumed body
    // that asks a SECOND question was refused by `ProgramCall.park` ("program is
    // parked"): the state still read parked while the body ran on. The operator's
    // message names no deadline, so the resumed run has none — the same rule
    // core's `takeSite({ deadline: null })` applies.
    if (entry?.program?.state === "parked" && typeof entry.program.takeSite === "function") {
      entry.program.takeSite({});
    }
    // `resume` is `settle` + `continuation`: it wakes the parked expression and
    // answers the body's own outcome (`completed` / `failed` / `parked`).
    const outcome = await this.#parks.resume(name, value);
    if (outcome === false) return false;
    // Same completion step as `#awaitContinuation`: a body that ends here is a
    // program that must reach its terminal state, because its call's `run`
    // answered at the park and will never see it.
    if (entry?.call !== null && entry?.call !== undefined && typeof entry.call.settleContinuation === "function") {
      entry.call.settleContinuation(outcome);
      if (outcome?.status !== "parked") {
        this.#tenure.noteFinished(name);
        this.#parks.close(name);
      }
    }
    const receipt = this.#continuationReceipt(name, entry, outcome, value, null);
    // `#asked` is the CALLER's business: `resumeAsked` clears the answered
    // question before it wakes the body, and a body that asks again records the
    // new one during the wake-up. Clearing here would erase that newer fact.
    return receipt;
  }

  /**
   * A Plan's `review([])` hand-off was submitted and the host reports the
   * Manager session it started (根 §2.2). The flag is Plan's half of its only
   * legitimate stop: the successor verified the previous tenure, and the draft
   * is on its way to a Manager.
   *
   * A receipt without a session id is not a hand-off: the host says whether a
   * Manager exists, and `planHandoff` already turned a missing one into a
   * failure. Only the accepted answer reaches here.
   */
  noteHandoff(receipt) {
    if (receipt === null || typeof receipt !== "object") return this.#handedOff;
    // Two shapes reach here, and both are the same fact: the relay PORT's own
    // receipt (`{ ok, sessionId }`), and a review receipt that carries it under
    // `started` (`ReviewMember` answers `{ ...receipt, started }`). Reading only
    // the top level made the surface's hand-off invisible.
    const started = receipt.started !== null && typeof receipt.started === "object" ? receipt.started : null;
    const accepted = receipt.ok === true || started?.ok === true;
    const sessionId =
      typeof receipt.sessionId === "string" && receipt.sessionId.length > 0
        ? receipt.sessionId
        : typeof started?.sessionId === "string" && started.sessionId.length > 0
          ? started.sessionId
          : null;
    if (!accepted || sessionId === null) return this.#handedOff;
    this.#handedOff = true;
    return this.#handedOff;
  }

  // ---------------------------------------------------------------------
  // nudge: a refused natural stop is recorded, and continued when the host
  // can still take a prompt
  // ---------------------------------------------------------------------

  /**
   * Remember that this session refused to stop (根 §2.2): the role, the
   * decision's reason and the text the model is owed. The record is what a
   * reader (a test, an operator's trace) uses to tell a continued turn from a
   * silent stop; `delivered` is filled by `deliver`.
   */
  noteNudge({ role = this.#role, reason = null, text = null } = {}) {
    this.#nudge = { role, reason, text, delivered: false };
    return this.#nudge;
  }

  get nudge() {
    return this.#nudge === null ? null : { ...this.#nudge };
  }

  // A nudge whose exit was reached is no longer owed: the request boundary
  // drops it instead of appending a stale instruction (根 §2.2).
  clearNudge() {
    this.#nudge = null;
    return this;
  }

  /**
   * Continue the session with a real prompt. The host's own steering channel is
   * `emitEvent("steer_message", { sessionId, prompt })`, which the runtime
   * queues and consumes at the next request (`consumePendingUserMessage`); that
   * is the only way to drive another turn from a hook.
   *
   * The answer is the channel's, never a claim: `{ ok: false, reason:
   * "NO_CONTINUATION_CHANNEL" }` when setup carried no `emitEvent` (the
   * sandbox always does), and `{ ok: false, reason }` when the channel throws.
   */
  async deliver(text) {
    const seam = this.#operator;
    if (typeof text !== "string" || text.length === 0) return { ok: false, reason: "EMPTY_NUDGE" };
    if (seam === null || typeof seam?.steer !== "function") {
      return { ok: false, reason: "NO_CONTINUATION_CHANNEL" };
    }
    // The session this seam was built for, or the one this state belongs to. A
    // setup that carried no `ctx.session.sessionId` still knows the id it was
    // given; passing `null` would make the host fall back to the root session,
    // which is the wrong conversation whenever this session is a started one.
    const target = this.#ctx?.session?.sessionId ?? this.#sessionId;
    let answer;
    try {
      answer = await seam.steer(target, text);
    } catch (error) {
      answer = { ok: false, reason: "CONTINUATION_FAILED", message: String(error?.message ?? error) };
    }
    const ok = answer?.ok !== false;
    if (this.#nudge !== null) this.#nudge.delivered = ok;
    return ok ? { ok: true } : { ...answer, ok: false };
  }

  /**
   * The operator's next real message, seen at a request boundary. A program
   * waiting on `ask` is resumed with those words and the waiting flag is
   * cleared; the words themselves stay in the request, because they are the
   * operator's turn and Scope reads them as one.
   *
   * Returns the resume receipt, or null when nothing was asked.
   */
  resumeAsked(messages) {
    if (this.#asked === null) return null;
    const words = lastOperatorText(messages);
    if (words === null) return null;
    const asked = this.#asked;
    // One accounting for both routes into a parked `ask`: the answer is
    // injected, the park table stops reporting the name, and the question is
    // answered once. `asked.resume` is the owning call's wake-up, which also
    // runs the continuation.
    //
    // The record is cleared AFTER the wake-up, not before: a body that asks a
    // second question parks again during the wake-up, and clearing first would
    // delete the record that continuation just wrote. `resume` itself clears the
    // answered one when the body did not park again, so the order here only has
    // to avoid erasing the newer fact.
    //
    // The request boundary does not wait for the continuation: it hands the
    // operator's words to the body and returns, and the body reports through its
    // own receipt. The question IS answered the moment the body is woken, so the
    // record is cleared before the wake-up — a body that asks again parks again
    // during it and records the new question, which must survive.
    this.#asked = null;
    void asked.resume(words);
    return { name: asked.name, answer: words };
  }

  scopeNotice() {
    const scope = this.#tenure.scope().table.get(this.#sessionId);
    return scope === null ? null : scope.notice();
  }

  // The system prompt of this session's requests, returned once. A Manager gets
  // `COMMON + MANAGER` plus the director message; coder and devops get
  // `COMMON + WORKER`, byte-identical to each other; Plan is outside the
  // projection and gets its own role segment (根 §5, §2.2).
  //
  // The director message belongs to the Manager alone. A top-level session whose
  // metadata names `coder` or `devops` must get the worker composition, never the
  // Manager one: the role decides the prompt, not the fact that the session is
  // top-level.
  //
  // It is returned once per session because the host keeps what a request
  // carried: returning it again on every request would repeat it in a history the
  // host persists. `role` defaults to the role this session was resolved to.
  //
  // Every other role reaches `systemPromptFor`, which refuses: an unrecognized
  // role must not quietly receive the worker prompt, because a broken role
  // string would then look like a healthy session.
  async systemPrompt(role = this.#role) {
    if (this.#prompted) return null;
    this.#prompted = true;
    if (role === ROLES.PLAN) return PLAN_PROMPT;
    if (role === ROLES.MANAGER) return directorPromptFor(systemPromptFor(ROLES.MANAGER));
    return systemPromptFor(role);
  }

  // The request copy: the relay barrier first (a successor sees nothing of the
  // retired tenure), then Scope, then Pair Hint.
  //
  // `kind` is what the request is FOR (core's `REQUEST_KINDS`). Cline has one
  // request path today (`beforeModel` for the session's own model calls), so it
  // is always `main`; the parameter exists so a side request — a hook-driven
  // model call that the host discards — can be labelled here instead of
  // silently becoming the chain. See `host-opencode/spec/context-hook.mjs`
  // `requestKind` for the shape a host-specific classifier takes.
  async projectMessages(messages, { kind = "main", childAgentId = null } = {}) {
    // A delegated runtime receives this session's hooks (a worker that lost them
    // would have a `js-worker` tool with no program behind it), so THIS method
    // also sees the child's requests. The Pair Hint chain is one session's
    // conversation, and a child's request is a DIFFERENT conversation: chaining
    // it against the parent's epoch refused the child's very first turn with
    // `PREFIX_DRIFT` ("a message already shown would change", measured on a live
    // fork), because the child's messages are not an extension of what the
    // parent presented.
    //
    // A child therefore gets the Scope projection and the CUE rendering — both
    // are about THIS session's beats — and its own pair-hint epoch, anchored on
    // its own first request. `childAgentId` names which child, so two workers do
    // not share one epoch either.
    if (typeof childAgentId === "string" && childAgentId.length > 0) {
      return await this.#projectForChild(messages, childAgentId);
    }
    // §6.3 first: a structured tool result is rendered as CUE before anything
    // else looks at the copy, so the Scope projection, the Pair Hint's prefix
    // chain and the request itself all see the bytes the model will read. The
    // host's provider layer would otherwise turn a result it produced itself
    // (`{ error }` from a refused call, a program receipt) into JSON, escapes
    // and all. The operator's own messages are untouched: this rewrites
    // `tool-result` parts only.
    let current = toCueToolResults(messages);
    const fence = this.#tenure.fence;
    if (fence.successor !== null && fence.successor !== undefined) {
      // The cut lands on the successor's own operator turn, never on the user
      // message that answers a tool call of the retired tenure: a copy that
      // starts with such a message begins with an answer to a call it does not
      // carry, which the provider rejects. `-1` means the copy holds no turn of
      // the successor's own, and then the barrier sends its message alone.
      const cut = barrierCutIndex(current);
      // 根 §2.4 第 3 步: the successor reads the succession notice and the
      // operator's own words, and nothing of the conversation that triggered
      // the relay. The other two adapters write the notice into the copy; this
      // one must too, or the successor's first request is a bare operator turn
      // the model cannot tell from the retired tenure's own.
      // `0` is a VALID cut: the successor's own operator turn is the first
      // message of the copy, and dropping it would send the notice alone.
      // `-1` is the only "no turn of the successor's own" sentinel.
      current = [...successionNotice(fence), ...(cut >= 0 ? current.slice(cut) : [])];
    }
    const scope = this.#tenure.scope();
    // The Pair Hint's synthetic pair carries the empty tool name and the
    // `js-review` face is the Manager's evaluation channel: both are ours, not
    // a beat of any Scope, so the projection keeps them across a `resetScope`
    // exactly as the other adapters do (§6.2, §6.1).
    const clineItems = toItems(current);
    const projected = scope.project({
      sessionId: this.#sessionId,
      items: clineItems,
      pinned: PINNED_MEMBERS,
    });
    // The projection's OWN answer, before the half-pair filter: the Pair Hint
    // may re-base the chain for a replacement SCOPE made, never for one this
    // filter made.
    let scopeAnswer = null;
    let scopeGiven = null;
    let scopeAnswered = null;
    if (projected.ok === true) {
      scopeGiven = clineItems;
      scopeAnswered = projected.messages ?? [];
      scopeAnswer = (projected.messages ?? []).map((item) => item?.source).filter((message) => message !== undefined);
      // The projection's answer IS the copy, whatever its length: a `removeLast`
      // replaces one message with one message, and an equal-length copy is a
      // rewrite the lesson decided, not a no-op.
      current = selectKept(current, projected.messages ?? []);
    } else {
      // The projection refused (a call/result disagreement, a malformed record).
      // The CUE/barrier copy still goes out — the raw host transcript never
      // does — and the refusal leaves a durable line that names its shapes.
      this.#recordScopeRefusal(projected, clineItems);
    }
    const hint = await this.#tenure.pairHint(this.#root);
    // A request that does NOT extend what this session already presented is a
    // different conversation reaching the same hook object. That is what a
    // delegated runtime does: it receives this session's hooks (a worker that
    // lost them would have a `js-worker` tool with no program behind it), so its
    // first turn arrives here with messages that are not an extension of the
    // parent's copy. Chaining it against the parent's epoch refused it with
    // `PREFIX_DRIFT` ("a message already shown would change"), measured on a
    // live fork.
    //
    // The child is identified by that fact alone — it is the only reading
    // available here, and it needs no cooperation from the hook layer: a
    // request that cannot extend the chain is not part of it. Each such
    // conversation gets its own epoch, keyed by its own opening message, so two
    // workers never share one either.
    // The chain, not the previous copy: the journal keeps `{length, digests,
    // root}` and no full request copy (root §1.1 item 1), so a cold start can
    // still compare this request with what was presented. `chain`
    // is the pair-free view — the same view `copy` carries — so the comparison
    // is one rule, not two.
    const chain = hint.chain ?? null;
    if (hint.bound && Number.isInteger(chain?.length) && chain.length > 0) {
      const copy = toPairHintMessages(current);
      let extendsChain = true;
      try {
        extendsChain = hint.injector.extendsPrefix({ previous: chain, next: copy });
      } catch {
        // An unreadable comparison is not a licence to re-base: fall through to
        // the normal path, which decides (and refuses) on its own evidence.
        extendsChain = true;
      }
      if (!extendsChain) return await this.#projectForChild(messages, childKeyOf(copy));
    }
    // The host's own compaction rewrote the conversation and SAVED it, so every
    // later turn is built from the compacted projection. That is a durable
    // rewrite the spec owns — the `resetScope` shape, not a branch — but Scope
    // is not its author, so the boundary cannot see it from the items. It is
    // passed as a REPORT the host produced, which is the only form the boundary
    // accepts: the mark Cline itself puts on a summary
    // (`isCompactionSummaryMessage`), not an inference from a copy that merely
    // looks shorter.
    //
    // A compaction may restart the chain ONCE — when it first appears.
    //
    // `isCompactedCopy` stays true for the rest of the session: every later
    // request is built from the compacted projection, so it still carries the
    // summary at its head. Reporting `history-compacted` on all of them would
    // make the boundary re-base on every request, and an unauthorised rewrite of
    // a message inside the compacted history would then be blessed instead of
    // refused (found by the adversarial generator: a `compacted` turn followed
    // by a rewrite was accepted).
    //
    // The rewrite is NEW when the copy carries a summary the presented copy did
    // not. Presence alone is not the test: every request after a compaction is
    // built from the compacted projection, so a summary is present from then on.
    // Identity is: the host merges a second compaction into NEW summary text, so
    // that is a new rewrite, while a request built from the same compacted
    // history is not.
    // The summary's DIGEST, not its text: the chain keeps one digest per
    // message (`chain.digests`), and after a cold start there is no previous
    // copy to read the text from. The PAIR-FREE view is the host copy, so its
    // first entry is the summary; `digests` itself may carry the synthetic pair
    // at index 0 in a copy the host handed back with the pair already in it.
    const presentedSummary =
      Number.isInteger(chain?.length) && chain.length > 0 ? chain.pairFreeDigests?.()[0] ?? null : null;
    const compacted = isCompactedCopy(current) && compactionDigest(current) !== presentedSummary;
    // The barrier rewrote the copy, so the successor's first request cannot
    // start with the bytes the predecessor presented. That cut is one
    // legitimate chain break (根 §2.4 第 3 步): the frozen text and the epoch stay,
    // and the successor's first request becomes the new anchor, once.
    //
    // A Scope projection that removed or rewrote messages is the OTHER one
    // (§6.2): `resetScope` prunes old beats and `removeLast` replaces the last
    // one in place, and neither can extend what was presented. The test is the
    // observed copy, never "a resetScope record exists" — a settled reset whose
    // boundary is outside this copy prunes nothing, and anchoring there would
    // throw away the comparison that catches a real rewrite.
    const boundary = projectionBoundary({
      durableRewrite: compacted ? REBASE_REASONS.HISTORY_COMPACTED : null,
      presented: chain,
      projected: toPairHintMessages(current),
      incoming: messages,
      givenItems: scopeGiven,
      answeredItems: scopeAnswered,
      projectedBy: scopeAnswer,
      filtered: current,
      pairFree: (copy) => hint.injector.withoutInjection(copy),
      erased: this.#tenure.scope().erasedCount(this.#sessionId),
    });
    if (this.#tenure.anchorOfFence() === true) hint.anchor(toPairHintMessages(current), { reason: REBASE_REASONS.RELAY });
    else if (shouldAnchor(boundary)) hint.anchor(toPairHintMessages(current), { reason: boundary.reason });
    // The presentation is frozen for its whole epoch, so a handle list that
    // differs from the frozen one is only visible after a new epoch. Without
    // this, the idle hint would never list a park that appeared mid-session.
    const handles = this.#tenure.parkedNames();
    if (hint.text !== null && [...hint.handles].sort().join("\n") !== handles.join("\n")) {
      await hint.newEpoch();
    }
    hint.setHandles(handles);
    if (hint.bound) {
      const pair = await hint.project({ messages: toPairHintMessages(current), kind });
      if (Array.isArray(pair?.messages)) current = pair.messages;
    }
    return current;
  }

  // One delegated runtime's request copy (§6.1–§6.3).
  //
  // A worker's conversation is its own: its messages are not an extension of
  // what the parent presented, so chaining them against the parent's epoch
  // refused the child's first turn with `PREFIX_DRIFT` (measured on a live
  // fork: `finishReason: "error"`, `text: "a message already shown would
  // change"`). The Scope projection still applies — those are this session's
  // beats and the child reads the same book — and the CUE rendering still
  // applies, because it is about the bytes the model reads. What does NOT apply
  // is the parent's prefix chain; the child gets its own, anchored on its own
  // first request.
  async #projectForChild(messages, childAgentId) {
    let current = toCueToolResults(messages);
    const scope = this.#tenure.scope();
    const items = toItems(current);
    const projected = scope.project({
      sessionId: this.#sessionId,
      items,
      pinned: PINNED_MEMBERS,
    });
    if (projected.ok === true) {
      current = selectKept(current, projected.messages ?? []);
    } else {
      this.#recordScopeRefusal(projected, items);
    }
    const hint = await this.#tenure.pairHintFor(this.#root, childAgentId);
    if (hint.bound) {
      // A fresh child hint has presented nothing, so its `project` decides the
      // placement and starts the chain itself — no explicit anchor is needed,
      // and none is correct: the child's first request IS its anchor.
      const pair = await hint.project({ messages: toPairHintMessages(current), kind: "main" });
      if (Array.isArray(pair?.messages)) current = pair.messages;
    }
    return current;
  }

  // Remember that the gate refused this call, so `settle` can skip it too.
  noteSkipped(callId) {
    if (typeof callId !== "string" || callId.length === 0) return false;
    this.#skipped.add(callId);
    return true;
  }

  // Whether this call was refused before it ran. Removes the entry: a call id
  // belongs to one call.
  takeSkipped(callId) {
    if (typeof callId !== "string" || callId.length === 0) return false;
    return this.#skipped.delete(callId);
  }

  admit({ member, input }) {
    return this.#tenure.scope().admit({
      sessionId: this.#sessionId,
      member,
      annotation: input?.scope,
    });
  }

  async settle({ member, input, callId }) {
    const result = await this.#tenure.scope().settle({
      sessionId: this.#sessionId,
      member,
      annotation: input?.scope,
      turnId: this.#turn,
      callId,
    });
    if (result.ok === false) return result;
    return { ...result, notice: this.scopeNotice() };
  }

  // ---------------------------------------------------------------------
  // Workers
  // ---------------------------------------------------------------------

  async startWorker({ name, verboseIntent, role, start = true }) {
    // The guarded door (root §7.2, gap 2): every delegated runtime this session
    // builds gets the worker face, whatever list the host or a frontmatter
    // narrowed it to.
    const delegated = await guardedDelegatedApi(() => this.workerTools());
    if (delegated === null) {
      return {
        started: false,
        rejection: "HostCapabilityMissing",
        message: "the host did not provide @cline/core createDelegatedAgent to the plugin",
        detail: { searched: "bare specifier, CLINE_CORE_MODULE, argv/execPath/cwd ancestors, installation layouts" },
      };
    }
    // A worker runs the TASK model (root §1.1 item 6): the operator's
    // `HERDCHESTRA_TASK_*` pair when set, the session's own ladder otherwise.
    // A worker started on a GUESSED connection would answer from the wrong
    // account, so a session with no reading anywhere is refused instead of
    // defaulted. `source` says which ladder answered (`task` / `main`).
    //
    // The model alone is not enough to RUN: the config provider forwards
    // exactly what it is given, so the credentials the host stores for that
    // provider (`providerSettings`) travel with it. Without them the runtime
    // starts and dies at the provider on "no API key" — measured live.
    const { model, source, credentials, credentialSource } = await this.resolveTaskConnection();
    if (model === null) {
      return {
        started: false,
        rejection: "MODEL_UNKNOWN",
        message:
          "no source named the task provider and model (no HERDCHESTRA_TASK_* pair, no request yet, none in the setup context, none in the host session record)",
        detail: { tried: ["taskEnvironment", "request", "context", "sessionRecord", "environment"] },
      };
    }
    const extensions = this.#extension === null ? [] : [this.#extension];
    const connection = {
      providerId: model.providerId,
      modelId: model.modelId,
      cwd: this.#root,
      extensions,
      // Credentials first, then the identity: a host that stored a key for this
      // provider is the authority for it, and the model half above is the
      // authority for the model.
      ...(credentials === null ? {} : { ...credentials, modelId: model.modelId }),
    };
    const configProvider = delegated.createDelegatedAgentConfigProvider(connection);
    // `start: false` builds a lane's runtime without its first turn (§4.2 step
    // 4: the group record is committed before any lane runs). The runtime still
    // carries the worker face and the steer channel; only the turn waits.
    return runtimeHandle({
      name,
      verboseIntent,
      role,
      start,
      create: delegated.createDelegatedAgent,
      configProvider,
      tools: this.workerTools(),
      extensions,
      cwd: this.#root,
      // `systemPromptFor` IS the `COMMON + WORKER` composition for coder and
      // devops (`compose/prompt/projection.mjs`); a worker's prompt must be that
      // exact text, not a local copy of it.
      prompt: systemPromptFor(role),
      modelSource: source,
      credentialSource,
    });
  }
}
