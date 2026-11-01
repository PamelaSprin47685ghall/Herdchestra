// The extension entry point (spec §7.3).
//
// OMP loads a default-exported factory `(pi: ExtensionAPI) => void`. The default
// channel is an installed plugin: the host enumerates `node_modules/<pkg>` under
// the user plugin root and `<anchor>/.omp/plugins`, reads the package's `omp`
// manifest and loads the `extensions` entries (loader.ts `getEnabledPlugins`,
// `resolveManifestEntryFiles`); `--extension <path>` loads a file directly.
//
// ## What happens, in order
//
// 1. **The factory registers the five tools.** This is the only ordering that
//    works: the host snapshots the registered tool set while building the
//    session, and a tool registered later reaches the table only through the
//    runner's late-registration path. Registering inside `session_start` raced
//    that path on the real host — the hooks fired while `js-*` never appeared in
//    the tool table. A tool definition is session-independent (the session and
//    role arrive in `execute`'s `ctx`), so factory-time registration is both
//    correct and sufficient.
// 2. **`session_start` installs this session's hooks and narrows its visible
//    set.** The event is the first one carrying `ctx`, so the session is built
//    there: role from `ctx.agent` (never from `mode`, `depth` or
//    `sessionModes`), then programs, review ledger, Scope, Pair Hint and lanes.
//    OMP replays the latest `session_start` to an extension added after it
//    (runner.ts:561-562), so the handler installs at most once per instance;
//    the runner is rebound per session, so a fresh instance is what a second
//    session needs.
// 3. **The narrowing runs at session start and when the review phase changes.**
//    `setActiveTools` inside `before_agent_start` makes the host re-run policy
//    preparation (spec §7.3), so the phase is narrowed only when it actually
//    changes — once per round, not once per request — and the prompt it returns
//    is a pure function of the role, which is what that re-run requires. What
//    the model sees is the narrowing; enforcement is the `tool_call` gate in
//    hooks.mjs.
//
// ## `js-review` follows the evaluation window (spec §2.3)
//
// `js-review` is registered but is not a permanent face. It is visible for a
// Manager only while the session's evaluation window is open: `openEvaluation`
// opens it at the host's request boundary (`before_agent_start`), and the
// window shuts when the round's FIRST review is accepted (`onReviewAccepted`).
// A refused review leaves it open: the Manager has not judged. Only the Manager
// ever owns it (`ownFace`), and the window is core's `ReviewWindow` — this
// adapter keeps no second counter that could drift from it.
//
// ## `dispatch` is absent on the real host
//
// Measured against omp 18.14.1: the loader calls `factory(api)` with ONE
// argument (loader.ts:454 `await factory(api)`), so `options.dispatch` is never
// supplied; `ctx.invokeTool` exists only on a tool's context, delegates only to
// a native built-in of the SAME name (extensions/types.ts:877), and is absent
// on the real `session_start` context. `ExtensionAPI` has no general tool
// dispatcher. The devops investigation (`Plan`'s `this.devops`) still answers
// `NO_HOST_DISPATCH` rather than pretend.
//
// `fork`/`join`/steer do NOT need it: a worker is a real session started
// through the SDK the host injects as `api.pi` (`lane.mjs`), not the `task`
// tool. The Manager's question to the operator is `this.ask` = park + the
// event's own `ctx.abort` (`surface.mjs` `askOperator`), and the Plan handoff's
// Manager session is a lane like any other (`surface.mjs` `startManager`).

import { RoleSession, sessionIdOf } from "./surface.mjs";
import {
  roleOfAgent,
  ROLE,
  isWorkerRole,
  REPLACED_TOOLS,
  MANAGER_FORBIDDEN,
  MANAGER_TOOLS,
  WORKER_LANE_TOOLS,
  WORKER_TOOLS,
  PLAN_TOOLS,
  SCOPE_QUERY_TOOLS,
} from "./host-facts.mjs";
import {
  programTool,
  MANAGER_DESCRIPTION,
  WORKER_DESCRIPTION,
  REVIEW_DESCRIPTION,
  PLAN_DESCRIPTION,
} from "./program-tools.mjs";
import { forkTool, joinTool, negotiateTool, askPreviousScopeTool } from "./worker-port.mjs";
import { workerSdk as workerSdkOf, unwrappedPi } from "./lane.mjs";
import {
  createToolCallHandler,
  createToolResultHandler,
  createBeforeAgentStartHandler,
  createContextHandler,
  createTurnStartHandler,
  createTurnEndHandler,
  createAgentEndHandler,
} from "./hooks.mjs";
import { createSubagentSpawnHandler } from "./spawn-gate.mjs";
import { gateStateRecord, recordRequest, requestLogFile, requestRecord } from "./drift-log.mjs";
import { isSideChannelRequest } from "./request.mjs";

// The worker-control tools and the Scope snapshot queries. They are host tools
// of ours, not program faces: the model needs them between program runs (the
// budget a call is about to spend is decided before the call, and the two
// snapshot members are exempt from that budget, spec §6.2).
export const CONTROL_TOOLS = Object.freeze(["fork", "join"]);
export const TOOL_NAMES = Object.freeze(["js-manager", "js-worker", "js-review", "plan", ...CONTROL_TOOLS]);

// The face lists live in `host-facts.mjs` (they are facts about which tools this
// adapter registers) and are re-exported here for callers of the entry module.
// Keeping one definition matters: `surface.mjs` builds a lane roster and needs
// them too, and it must not import this module to get them (that would be a
// cycle).
export { MANAGER_TOOLS, WORKER_LANE_TOOLS, WORKER_TOOLS, PLAN_TOOLS, SCOPE_QUERY_TOOLS } from "./host-facts.mjs";

// Faces each role must not see. A face is one of the three program tools; the
// control tools (`fork`, `join`) are never faces. `js-review` is the Manager's
// evaluation face (spec §2.3); it is deliberately absent from every visible set
// and is added by name only while the session's evaluation window is open.
const MANAGER_HIDDEN = Object.freeze([...MANAGER_FORBIDDEN, "js-worker"]);
// A worker never owns the Manager's faces. It DOES keep `fork`/`join`: spec §4
// defines them as the subagent-scheduling primitives without restricting the
// caller, and §4.2 explicitly gives a worker `fission` — a worker that can
// expand one identity into parallel lanes can equally start another worker.
const WORKER_HIDDEN = Object.freeze([...REPLACED_TOOLS, "js-manager", "js-review"]);
// A Plan keeps `fork`/`join` out as well: §2.2 gives it exactly five
// capabilities and no dispatch — the only agent it reaches is `this.devops`,
// which is synchronous and single-flight. Starting a worker from the planner
// would let it build instead of plan.
const PLAN_HIDDEN = Object.freeze([
  ...REPLACED_TOOLS,
  "js-manager",
  "js-worker",
  "js-review",
  ...CONTROL_TOOLS,
  // Plan books no Scope (root §2.2: it has five capabilities), so a snapshot of
  // a book it never spends would be a fact about somebody else's budget.
  ...SCOPE_QUERY_TOOLS,
]);
const FACE_NAMES = Object.freeze(new Set(["js-manager", "js-worker", "js-review", "plan"]));

// The face a lane of this role is created with. A lane is not always a worker:
// a Plan hand-off starts a Manager session through the same machinery (§2.2),
// and a lane must never be handed a face its role does not own.
const laneFaceFor = (laneRole) => {
  // No reading, no face. An `unproven` session (the host had not filled its
  // `agent` yet) must not be handed a worker face by falling through to the
  // last branch, and it must not be handed the Manager's either.
  if (laneRole === ROLE.UNPROVEN) return [];
  if (laneRole === ROLE.MANAGER) return MANAGER_TOOLS;
  if (laneRole === ROLE.PLAN) return PLAN_TOOLS;
  return WORKER_LANE_TOOLS;
};

// The visible set for a role. Every replaced tool is dropped; the Manager also
// drops every other tool it must not call. Names OMP does not have are ignored.
export const visibleToolsFor = (role, allTools, extraTools = [], { evaluating = false } = {}) => {
  const names = (Array.isArray(allTools) ? allTools : [])
    .map((tool) => (typeof tool === "string" ? tool : tool?.name))
    .filter((name) => typeof name === "string" && name.length > 0);
  // The review face is not in any always-visible set: spec §2.3 opens it only
  // while the session's evaluation window is open. `evaluating` is that window
  // — opened by the host's request boundary (`before_agent_start`) and shut by
  // the round's first accepted review, so the face is present exactly while
  // §2.3 says it may be.
  //
  // Only the Manager owns the face (`ownFace`), so a worker passing
  // `evaluating: true` still does not receive it.
  const reviewOpen = evaluating === true && role === ROLE.MANAGER;
  const hiddenFor = (r) => {
    if (r === ROLE.MANAGER) return MANAGER_HIDDEN;
    if (r === ROLE.PLAN) return PLAN_HIDDEN;
    return WORKER_HIDDEN;
  };
  const forbidden = new Set([
    ...hiddenFor(role),
    ...(reviewOpen ? [] : ["js-review"]),
  ]);
  // A face this role may not own is dropped even when the list passed in has
  // it: the caller's list is whatever the host currently offers, and the
  // narrowing is what makes the face invisible.
  const kept = names.filter((name) => !forbidden.has(name) && !(FACE_NAMES.has(name) && !ownFace(role, name)));
  // The role's own tools are ALWAYS kept, whether or not the host's current
  // active set already lists them. `getActiveTools()` reports the enabled set
  // as it stands, and at session start the tools this extension just registered
  // are not in it yet (they enter the table through the runner's
  // late-registration path, sdk.ts:5189). Filtering by `available` here would
  // drop exactly the tools this adapter exists to expose, which is the bug the
  // real host showed: `js-*` registered, hooks firing, tools absent.
  //
  // `extraTools` names host tools this extension did not register but must keep
  // in the visible set (the harness's own tool face). They are added only when
  // they exist, so a name the host does not have is never invented.
  const available = new Set(names);
  // An unproven role has no face at all: the reading failed, so there is
  // nothing to grant. Returning the caller's list unchanged would leave whatever
  // the host happened to offer, which is exactly the guess this refusal exists
  // to prevent.
  if (role === ROLE.UNPROVEN) return [];
  // A Plan's own face is `plan` and nothing else (§2.2).
  const ownList = role === ROLE.MANAGER ? MANAGER_TOOLS : role === ROLE.PLAN ? PLAN_TOOLS : WORKER_TOOLS;
  const own = ownList.filter((name) => !forbidden.has(name));
  // The baseline is whatever the session offered before this adapter narrowed
  // it, so it can contain faces this role does not own. It must pass the SAME
  // ownership test as the caller's list: filtering it by `forbidden` alone let
  // `plan` through to a coder, because `plan` is not in `WORKER_HIDDEN` — it is
  // excluded by `ownFace`, which this path never consulted.
  const extra = [...extraTools].filter(
    (name) => available.has(name) && !forbidden.has(name) && !(FACE_NAMES.has(name) && !ownFace(role, name)),
  );
  // The review face is added by name, not by presence in the host's list: it is
  // registered by this adapter and the host's active set at `session_start` does
  // not yet contain it.
  const review = reviewOpen ? ["js-review"] : [];
  return [...new Set([...kept, ...extra, ...own, ...review])];
};

// The one face a role may run, or null for `fork`/`join`/host tools.
const ownFace = (role, name) => {
  if (name === "js-manager" || name === "js-review") return role === ROLE.MANAGER;
  // The planner owns ONLY `plan` (root spec §2.2: five capabilities, no
  // `glob`/`grep`/`exec`). It must not inherit the worker face, which carries
  // `call`, `rm`, `mv` and the rest of the file surface.
  if (name === "plan") return role === ROLE.PLAN;
  if (name === "js-worker") return role === ROLE.CODER || role === ROLE.DEVOPS;
  return true;
};

// Whether this session was started by a Herdchestra `fork` (spec §4.2
// `InvalidOrigin`). OMP reports the spawning agent in `ctx.agent.parentId`, and
// our `fork` names every worker it starts, so a subagent whose parent exists and
// whose own definition is one of ours was forked by us. A `/tan` clone or a
// plain `task` spawn carries neither fact.
export const forkedByHerdchestra = (agent) => {
  if (agent === null || typeof agent !== "object") return false;
  if (agent.kind !== "sub") return false;
  if (typeof agent.parentId !== "string" || agent.parentId.length === 0) return false;
  return isWorkerRole(roleOfAgent(agent));
};

// The adapter a worker session created through the SDK runs on.
//
// Why not the Manager's own tool objects: every tool closure reads the session
// that owns it, so handing a Manager session's tools to a worker would make the
// worker run its programs against the Manager's registry, ledger and parks.
// Each started worker therefore gets an adapter of its own, built by
// `workerToolsFor` in `HostAdapter`; this helper is that factory.
//
// `pi` is the worker's session itself: a session created through the SDK never
// receives an `ExtensionAPI`, and the session is what the host gives that worker
// in place of one (its coding-agent module lives on `session.pi`).
//
// `install(session)` does two things at once, after the host has created the
// session and before the handshake and the first turn:
//
//   - it binds the adapter to that session, so the worker's tools read the
//     worker's own state;
//   - it installs the face THAT SESSION'S ROLE owns on its OWN tool table,
//     because a session created through the SDK gets no extension runtime and
//     so cannot be handed tools through `setActiveTools`. Registering them
//     through the parent's `registerTool` would put the worker's tools in the
//     Manager's table, where the worker never looks.
//
//     The face is derived from `adapter.role`, so omitting `only` is safe: a
//     coder's session can never receive `plan`. `only`, when given, can only
//     NARROW inside the owned face — it cannot widen it. Trusting the caller to
//     pass the right list was the earlier mistake: a caller that forgot got
//     every registered face, the planner's included.
//
// `dispose()` releases the adapter when the start is refused.
export const workerSessionTools = (pi, options = {}) => {
  const adapter = new HostAdapter({
    pi,
    ctx: null,
    dispatch: options.dispatch ?? null,
    // A worker's roster is its OWN, opened when the worker binds: sharing the
    // table that started it would put the worker's children in the Manager's
    // roster and name the Manager as their parent. `lanes` is accepted only for
    // a caller that already owns a table (fission hands one over).
    lanes: options.lanes ?? null,
  });
  // The tree this session's state belongs to. A worker created by a lane has no
  // root of its own, and deriving one from `projectRoot()` reads a process-wide
  // variable that any other session may have set. The creator says which tree
  // it started from, so two sessions never disagree about where they write.
  const root = options.root ?? null;
  // The factory comes from the session the host returned (`session.pi`); a
  // session without one reports `NO_SESSION_FACTORY` from the lane, never a
  // borrowed factory.
  return {
    adapter,
    // `only` is the face this build is for. A worker session must not receive
    // the planner's face (or the Manager's): the session's tool table is what
    // the host offers that session, so registering everything here would hand a
    // worker `plan` — a face it can neither use nor be trusted with. The lane
    // passes the same list it names in `toolNames`.
    // `role` is the identity this session runs as. It is NOT guessed from
    // `session.agent`: a session the host built may carry no agent at all, and
    // `roleOfAgent(undefined)` answers UNPROVEN — so a bare install would have
    // granted no face at all, and a session the host built would run nothing.
    // This helper builds a WORKER, so that is the default; a lane that knows
    // otherwise (a Manager lane) says so.
    install: async (session, { only = null, role = ROLE.CODER } = {}) => {
      // `forked` is a fact about WHO created this session. A lane created by a
      // `fork` starts a forked worker; a lane created by a Plan hand-off starts
      // a MANAGER session, which is not forked — `forked` also decides whether
      // the session keeps a review ledger and the `js-manager` ports, so
      // claiming it for a Manager would strip the very things it needs.
      adapter.bindSession(session, { forked: options.forked !== false, role, root });
      if (adapter.ctx !== session) return { ok: false, reason: "the worker's adapter did not bind its session" };
      // The session's OWN factory, installed here because `install` is the one
      // path every worker goes through (a lane, a fission group, a hand-built
      // session in a test). A worker's `fork` must start its children in its own
      // roster, and the only way it can is to hold an SDK that resolves to that
      // roster — the creator's SDK resolves to the CREATOR's, which is how a
      // worker's child came to be recorded in the Manager's journal (measured
      // 2026-11-01). Assigned after the bind, because the resolver needs the
      // roster the bind just opened.
      //
      // The BASE module is unwrapped first: a second `install` (the lane's own
      // after a caller's, which is how a hand-built worker in a test is bound)
      // must re-point the SDK at the roster the new bind opened. Without that,
      // a worker installed once before its roster existed kept a factory that
      // answered no table, and its nested `fork` failed with
      // SESSION_CREATE_FAILED.
      if (session !== null && typeof session === "object") {
        const workerFactory = workerSdkOf(unwrappedPi(session.pi ?? pi), () => adapter.session?.lanes ?? null);
        if (workerFactory !== null) session.pi = { ...(session.pi ?? {}), ...workerFactory };
      }
      // The worker's own table first. A host session exposes its tools as a
      // `Map` of name → definition; when it does not, fall back to the API the
      // builder was given (which is what a real host's `registerTool` writes).
      const table = session?.tools;
      // The face comes from the SESSION's own role, so a caller that omits
      // `only` cannot leak a foreign face: a coder's session never receives
      // `plan`, a Manager's never receives `js-worker`. `only` narrows further
      // when the lane already knows the face it asked for.
      const owned = laneFaceFor(adapter.role);
      const allowed = Array.isArray(only) ? only.filter((name) => owned.includes(name)) : owned;
      const face = adapter.tools().filter((tool) => allowed.includes(tool.name));
      for (const tool of face) {
        if (table instanceof Map) table.set(tool.name, tool);
        else await pi?.registerTool?.(tool);
      }
      return { ok: true };
    },
    boundSession: () => adapter.ctx,
    dispose: () => adapter.release(),
    tools: adapter.tools(),
  };
};

// One session's adapter: the session, the tools and the hooks for one role.
// Every bound adapter, by session id. A tool definition handed to a worker as a
// `customTools` entry carries this module's closures, not that worker's
// adapter, so it resolves the owning adapter from the calling session.
const ADAPTERS = new Map();

// Every adapter this process has built, registered or not. The registry answers
// "which adapter owns this session ID"; this list answers the other question a
// tool call asks: "is there an adapter bound to this very context?". A session
// an adapter was constructed with is bound from the constructor, and its id may
// name nothing the registry holds — refusing such a call as unbound would be
// wrong, because its owner is right there.
const LIVE = new Set();

// Empty the registry. For a test harness only: the registry is process-wide and
// keyed by session id, which is right for a host (a tool call must find the
// adapter that owns its session) and wrong for a test file that builds many
// adapters with the same fixture ids — a later test's call would then be routed
// to an earlier test's session. Resetting between tests makes that impossible,
// instead of relying on every fixture choosing a unique id.
export const resetAdapters = () => {
  ADAPTERS.clear();
  LIVE.clear();
};

// The adapter for the session a tool call runs in, or null when this process
// cannot resolve it.
//
// The registry is the ONLY answer. It used to fall back to the adapter that
// built the tool definition, and that fallback is a cross-session leak: the
// definition is registered once per runner (the factory registers every face
// before any session exists), so the fallback is "whichever session this
// adapter happened to be", not the calling one. A call from a session that never
// bound then read another session's request gate, another session's roster and
// another session's Pair Hint — measured 2026-11-01 (session `01a1958f`, one omp
// process, 13:22-13:24): the session left no file of its own, yet its tool calls
// were refused with a SIBLING session's `PREFIX_DRIFT` verdict, the same text
// that sibling had written to its own `.request.json` four minutes earlier.
//
// A caller with no answer must say so (see `unboundRefusal`), never guess.
export const adapterForCall = (ctx) => {
  const id = ctx?.sessionManager?.getSessionId?.();
  if (typeof id === "string" && id.length > 0 && ADAPTERS.has(id)) return ADAPTERS.get(id);
  return null;
};

// The adapter a TOOL DEFINITION may serve, or null.
//
// Same registry, one extra fact: an adapter whose bound context IS this very
// object owns the call, even when the id lookup cannot find it (a session with
// no manager id is registered under its `agent-<id>` name). Identity is not a
// guess — the adapter was bound to this object — and it is the only fallback
// allowed: an adapter that merely built the definition owns nothing.
export const adapterForTool = (ctx) => {
  const registered = adapterForCall(ctx);
  if (registered !== null) return registered;
  if (ctx === null || ctx === undefined) return null;
  for (const adapter of LIVE) {
    if (adapter.ctx === ctx) return adapter;
  }
  return null;
};

// Whether a tool call may be refused as unbound.
//
// A call with NO context is the host executing a definition outside any session
// (a direct `execute` with no fifth argument, a harness): there is no session to
// judge, and nothing can be borrowed, so the tool's own port answers as it
// always has (`NO_SESSION`, `NOTHING_TO_JOIN`). A call WITH a context that names
// a session this process never bound is different: a session exists, its gate
// and roster do not, and a refusal by name is the only honest answer.
export const unboundCall = (ctx) => ctx !== null && ctx !== undefined && adapterForTool(ctx) === null;

// What a call whose session this adapter cannot resolve is told.
//
// It is a refusal, not a pass: the gate that would judge this session does not
// exist here, and a call nobody can judge is exactly what every other refusal in
// this file prevents. The code is its own, so a reader can tell "this session
// never bound" apart from "this session's request was refused" — the two facts
// the 2026-11-01 incident could not tell apart from the transcript alone.
export const SESSION_UNBOUND = "SESSION_UNBOUND";
export const unboundRefusal = (toolName) => ({
  block: true,
  reason: `${SESSION_UNBOUND}: ${toolName} ran in a session this adapter never bound, so there is no request gate, roster or Pair Hint to judge it by; nothing is borrowed from another session`,
});

// The Scope snapshot of the calling session (§6.2), as the two exempt tools
// read it. Resolved per call like every other port here: a definition built at
// factory time would answer for the factory's session, and a worker runs these
// same definitions from its own adapter.
export const scopeSnapshotPort = (owner) => (member) => {
  const session = owner?.session ?? null;
  if (session === null || session === undefined) {
    return { ok: false, code: "NO_SESSION", member };
  }
  return session.scopeSnapshot(member);
};

export class HostAdapter {
  #pi;
  #ctx;
  #role;
  #session;
  #dispatch;
  #installed;
  #usable = false;
  #narrowFailure = null;
  #bound;
  #forked;
  #baseline;
  // Spec §2.3: `js-review` is open from a round's first request until that
  // round's first accepted review. The window itself is core's `ReviewWindow`,
  // owned by the session; this adapter only drives it.
  #evaluating = false;
  // The hook object, built once (`hooks()`). It holds the in-memory gate.
  #hooks = null;

  // `ctx` may be null at factory time: the host calls the factory before any
  // session exists. The role is then unknown and is read from the first event
  // that carries a context (`bind`), so tools registered at factory time work
  // from the first turn instead of waiting for a `session_start` that a session
  // created through the SDK never receives.
  constructor({ pi, ctx = null, dispatch = null, lanes = null }) {
    this.#pi = pi;
    this.#ctx = ctx;
    this.#dispatch = dispatch;
    LIVE.add(this);
    this.#role = roleOfAgent(ctx?.agent);
    this.#installed = false;
    this.#bound = ctx !== null;
    this.#forked = forkedByHerdchestra(ctx?.agent);
    this.#baseline = null;
    this.#session = new RoleSession({
      pi,
      // `pi` is the host's ExtensionAPI; `pi.pi` is the coding-agent SDK, which
      // is where `createAgentSession` lives (see lane.mjs).
      api: pi,
      ctx,
      role: this.#role,
      dispatch,
      forked: this.#forked,
      // Spec §2.3: the round's FIRST accepted review shuts the evaluation, so
      // the Manager's read-only review face goes away. `review` runs inside a
      // tool call, not inside `before_agent_start`, so narrowing again here does
      // not make the host re-run policy preparation (spec §7.3).
      onReviewAccepted: () => {
        // The window is the authority; this only mirrors it for the visible
        // set. A second accepted review in the same round finds the window
        // already shut and the face already gone.
        if (this.endEvaluation() !== false) return;
        // The re-narrow is best-effort: a host that refuses actions must not
        // turn an accepted review into a failure. The face simply stays visible
        // until the next narrowing, and the `tool_call` gate still refuses a
        // call that arrives anyway (hooks.mjs reads the same window).
        void this.narrowTools().catch(() => {});
      },
      // A worker's own roster, when this adapter is the worker's. The Manager's
      // adapter passes none: its roster is opened when it binds to its session.
      lanes,
      // The worker face: its own program face plus the scheduling primitives.
      // `workerToolsFor` runs once per started worker, AFTER that worker's
      // session exists, so the tools a worker runs are built for the worker
      // itself and read its own session — never this (Manager) adapter's.
      // `lanes` is the roster those tools will fork into (the calling session's
      // own, never this one's). A session created through the SDK has no
      // extension runtime, so the lane installs these tools on it directly.
      laneTools: {
        workerToolsFor: (workerPi, lanes, laneRole = ROLE.CODER) => {
          // The lane needs the whole build, not just the list: it installs the
          // tools on the worker's session and disposes the adapter if the
          // worker is refused. No roster is shared: the worker opens its own
          // when it binds, so its children are its own.
          // A Manager lane is NOT a forked session: it keeps its review ledger
          // and the Manager's ports, which a forked worker deliberately drops.
          // The worker's own SDK factory. A session created through it must
          // carry the WORKER's SDK, not this adapter's: its `fork` then creates
          // its child through a factory that resolves the worker's own roster
          // (`workerSdk` reads the table of the session that is forking).
          // Without this the child is created by the starter's factory and the
          // starter's roster claims it (measured 2026-11-01: the Manager's
          // journal received `worker-fork:w1-child` and its `join` answered
          // with the worker's child).
          const worker = workerSessionTools(workerPi, {
            dispatch: this.#dispatch,
            forked: laneRole !== ROLE.MANAGER,
            // The worker writes into the tree its owner belongs to, stated
            // explicitly rather than read from `projectRoot()` at bind time.
            root: this.#session.state?.root ?? null,
          });
          // The face follows the ROLE, not the fact that a session was created
          // by a lane: a Plan hand-off starts a Manager session the same way a
          // `fork` starts a worker (spec §6.1), and that session must get
          // `js-manager`, never `js-worker`.
          const face = laneFaceFor(laneRole);
          return {
            ...worker,
            tools: worker.tools.filter((tool) => face.includes(tool.name)),
          };
        },
        // The name set is resolved per lane for the same reason; the table's
        // default stays the worker face so `fork`/fission are unchanged.
        workerToolNames: [...WORKER_LANE_TOOLS],
        toolNamesFor: (laneRole) => [...laneFaceFor(laneRole)],
      },
    });
  }

  get role() {
    return this.#role;
  }

  get session() {
    return this.#session;
  }

  // The session this adapter serves, or null before binding.
  get ctx() {
    return this.#ctx;
  }

  get bound() {
    return this.#bound;
  }

  // Bind this adapter to the session a host event carries.
  //
  // The FIRST event establishes the identity: the role never changes inside one
  // runner, because the runner itself is per session. `forked` is read here too,
  // so a worker created through the SDK (which never emits `session_start`)
  // still knows it is a forked worker.
  //
  // Every LATER event refreshes the CONTEXT. This is not cosmetic: the host
  // builds a fresh `ExtensionContext` for each emit (`runner.ts`
  // `createContext`, one object per call), and only the object handed to a
  // given emit carries that moment's live facts. Keeping the `session_start`
  // context forever made `ctx.sessionManager` undefined from the first tool call
  // onward — measured live: from inside `js-manager`, `sessionManager`, `agent`,
  // `isIdle` and `abort` were all `undefined` — which silently disabled Scope
  // admission and left the deferred relay unable to commit at `agent_end`.
  //
  // Only the context is replaced. The session state, the roster, the ledger and
  // the ports stay exactly where they were: rebinding those would move a live
  // session's registry, which is a different (and wrong) thing.
  bind(ctx) {
    if (ctx === null || typeof ctx !== "object") return this;
    if (this.#bound) {
      this.#ctx = ctx;
      // The refresh is what makes every later hook read THIS event's live
      // context. It is called defensively and its failure is not swallowed
      // silently: a surface without it is a wiring defect, and the extension
      // stderr is the only place the host shows it (measured live:
      // `this.#session.refreshContext is not a function`, thrown on every
      // event after the first, which left the deferred relay reading a stale
      // `session_start` context and unable to commit).
      const surface = this.#session;
      if (surface !== null && typeof surface === "object" && typeof surface.refreshContext === "function") {
        surface.refreshContext(ctx);
      } else if (process.env.HERDCHESTRA_DEBUG === "1") {
        console.error("[herd] this adapter's session has no refreshContext; the event context was not adopted");
      }
      ADAPTERS.set(sessionIdOf(ctx), this);
      return this;
    }
    this.#ctx = ctx;
    this.#bound = true;
    this.#role = roleOfAgent(ctx.agent);
    this.#forked = forkedByHerdchestra(ctx.agent);
    this.#session.bind({ ctx, role: this.#role, forked: this.#forked });
    // The host's own version, recorded beside every request this session makes
    // (`drift-log.mjs`). `pi.VERSION` is the host's public constant (measured on
    // 18.14.2: `"18.14.2"`); a host that stops exporting it leaves the field
    // null rather than a guess.
    this.#session.state?.adoptHostVersion?.(this.#pi?.VERSION ?? this.#pi?.pi?.VERSION ?? null);
    // A tool definition travels as `customTools` into sessions this adapter
    // creates, so its `execute` may run inside ANOTHER session's adapter. The
    // registry is how the tool finds the adapter that owns the call — keyed by
    // the SAME id the call will look up (`ctx.sessionManager`), never only by
    // the ledger's name for the session. A host context that names the session
    // differently in the two places would otherwise be refused as unbound.
    ADAPTERS.set(sessionIdOf(ctx), this);
    const callId = ctx?.sessionManager?.getSessionId?.();
    if (typeof callId === "string" && callId.length > 0) ADAPTERS.set(callId, this);
    // The one line a diagnostic depends on. Every durable file this adapter
    // writes is named after the session that bound it, so a session that left
    // none cannot be told apart from a session that never bound — that is the
    // ambiguity session 01a1958f left (2026-11-01: a refusal in the transcript,
    // and no file anywhere to explain it). The binding is printed once, at the
    // moment it happens, under `HERDCHESTRA_DEBUG=1`: a session whose work was
    // refused and whose transcript shows no line here was never this adapter's.
    if (process.env.HERDCHESTRA_DEBUG === "1") {
      console.error(
        "[herd] bound",
        JSON.stringify({
          sessionId: sessionIdOf(ctx),
          role: this.#role,
          root: this.#session.state?.root ?? null,
          host: this.#pi?.VERSION ?? this.#pi?.pi?.VERSION ?? null,
        }),
      );
    }
    return this;
  }

  // Bind to a session this adapter created itself (a worker started through the
  // SDK). Such a session receives no host event, so the lane hands it over
  // directly, before the first turn. Only an unbound adapter accepts one.
  //
  // `forked` is a fact about WHO CREATED this session, and only the creator
  // knows it. It is passed in, never re-derived from the session's `agent`
  // object: the host builds that object itself (`sdk.ts:3571`), so a missing
  // `parentId` there says nothing about whether a Herdchestra `fork` started
  // this session. Re-deriving it is what refused a real worker's `fission`
  // with `InvalidOrigin`.
  // `role` is the identity the caller established for this session. It is
  // passed explicitly by `workerSessionTools.install`, because a session the
  // host built may carry no `agent` and `roleOfAgent(undefined)` answers
  // UNPROVEN — which would make a worker silently run no face at all.
  // `root` is the working tree this session's state belongs to. A session the
  // host built has no root of its own, so without one it fell back to
  // `projectRoot()` — the process-wide `HERDCHESTRA_PROJECT_ROOT` variable. Two
  // sessions bound while different tests held that variable wrote to whichever
  // root happened to be set, which made the suite nondeterministic. The lane
  // passes its owner's root instead, so a worker inherits the tree it was
  // started from.
  bindSession(session, { forked = true, role = null, root = null } = {}) {
    if (this.#bound || session === null || typeof session !== "object") return this;
    this.#ctx = session;
    this.#bound = true;
    this.#role = role ?? roleOfAgent(session.agent);
    this.#forked = forked === true;
    this.#session.bindSession({ session, role: this.#role, forked: this.#forked, root });
    ADAPTERS.set(sessionIdOf(session), this);
    return this;
  }

  // Drop this adapter's registration. A refused worker leaves no entry behind,
  // so a later call can never resolve to a session that never ran.
  release() {
    // A released adapter is a session that will serve no more calls, and its
    // worker roster will never end another turn. A `join` already waiting on
    // that roster is therefore released too, with a refusal that names the
    // disposal — otherwise it would stay pending for the life of the process,
    // which is the hang a host would suffer, not a test artefact.
    void this.#session?.lanes?.disposeAll?.().catch(() => {});
    for (const [id, adapter] of ADAPTERS) {
      if (adapter === this) ADAPTERS.delete(id);
    }
    return this;
  }

  // The five tools. Only the role's own face is active by default; the other
  // faces stay registered but inactive, so the model is not offered them and a
  // call that arrives anyway is refused by the gate.
  tools() {
    // Every closure below reads `this.#session` AT CALL TIME. Capturing it in a
    // local would pin the instance that existed when `tools()` ran — at factory
    // time that instance has no `ctx`, and the same tool definitions are handed
    // to worker sessions as `customTools`, where a pinned session is the wrong
    // one (measured: the worker's `js-worker` threw `undefined is not an object
    // (evaluating 'session.buildSurface')`).
    //
    // `buildSurface` returns `{ surface, program, parks }`: the tool needs the
    // receipt, the lifecycle, and the promise a park leaves pending.
    // The adapter that owns the CALLING session, not the one that built the
    // tool: the same definitions are handed to worker sessions as
    // `customTools`, and there the owner is the worker's own adapter.
    // A call this process cannot attribute is REFUSED, never served from
    // another session: the surface, the roster and the parks all belong to one
    // session, and a call served by the wrong one is the leak `adapterForCall`
    // no longer allows. The refusal is thrown so the tool's own `guard` turns it
    // into an error result naming `SESSION_UNBOUND`.
    // The owner is the adapter whose BOUND context is this call's context. A
    // definition handed to a session by `customTools` carries the builder's
    // closures, so "the adapter that built it" is not the answer; the session
    // this call runs in is. A call whose session this process never bound is
    // refused by name — never served from another session's surface or roster.
    // `null` is "no session at all": the definition's own port then answers its
    // usual `NO_SESSION` receipt. A call that DOES name a session this process
    // cannot resolve is refused by name instead.
    const ownerOf = (ctx, toolName) => {
      const owner = adapterForTool(ctx);
      if (owner === null && unboundCall(ctx)) throw new Error(unboundRefusal(toolName).reason);
      return owner;
    };
    const surfaceFor = (request) => {
      const owner = ownerOf(request?.ctx, request?.surfaceName);
      if (owner === null) throw new Error(unboundRefusal(request?.surfaceName ?? "this tool").reason);
      return owner.session.buildSurface(request);
    };
    const onClose = async (name, state, ctx) => {
      const owner = ownerOf(ctx, name);
      if (owner === null) return;
      owner.session.closeSurface(name);
      owner.session.releasePark(name, state);
    };
    // The one face this session runs by default. A planner runs `plan`; the
    // others keep the face their role already had.
    const primary =
      this.#role === ROLE.MANAGER ? "js-manager" : this.#role === ROLE.PLAN ? "plan" : "js-worker";
    return [
      programTool({
        surfaceName: "js-manager",
        label: "JS Program (Manager)",
        description: MANAGER_DESCRIPTION,
        surfaceFor,
        onClose,
        defaultInactive: primary !== "js-manager",
      }),
      programTool({
        surfaceName: "js-worker",
        label: "JS Program (Worker)",
        description: WORKER_DESCRIPTION,
        surfaceFor,
        onClose,
        defaultInactive: primary !== "js-worker",
      }),
      programTool({
        surfaceName: "js-review",
        label: "JS Program (Review)",
        description: REVIEW_DESCRIPTION,
        surfaceFor,
        onClose,
        defaultInactive: this.#role !== ROLE.MANAGER,
      }),
      programTool({
        surfaceName: "plan",
        label: "JS Program (Plan)",
        description: PLAN_DESCRIPTION,
        surfaceFor,
        onClose,
        defaultInactive: primary !== "plan",
      }),
      // The port is resolved per call, through the CALLING session's own
      // adapter — never the adapter that built this definition. At factory time
      // no session exists yet; a worker runs these same definitions from its own
      // adapter, and its roster must be the one `fork` writes and `join` reads.
      forkTool({ port: (ctx) => ownerOf(ctx, "fork")?.session.worker ?? null }),
      joinTool({ port: (ctx) => ownerOf(ctx, "join")?.session.worker ?? null }),
      // The two exempt Scope queries (§6.2). Exempt means: no annotation, no
      // retention spent, allowed when the budget is spent. Plan has no Scope,
      // so it does not receive them (`PLAN_HIDDEN`).
      negotiateTool({ port: (ctx) => scopeSnapshotPort(ownerOf(ctx, "negotiate")) }),
      askPreviousScopeTool({ port: (ctx) => scopeSnapshotPort(ownerOf(ctx, "askPreviousScope")) }),
    ];
  }

  // The hooks, built ONCE per adapter and kept.
  //
  // They were rebuilt on every call, and the `context` hook object owns the
  // in-memory half of the request gate (`gateHeld`): a rebuilt object starts
  // with no held refusal, so a gate that could not be written refused nothing
  // through the very next `tool_call`. The `tool_call` handler also closes over
  // the object the request path writes to, and the two must be the same one.
  hooks() {
    if (this.#hooks !== null) return this.#hooks;
    // Read at CALL TIME, never captured. `bind()` builds the session's state
    // from the host's context, and a hook closure that pinned the instance it
    // saw here would keep answering from the unbound one: no turn, no root, no
    // verdict — which is exactly how the request gate came to report
    // `REQUEST_GATE_UNWRITABLE` on a gate file that had just been written.
    // The same rule `tools()` states below applies to every closure here.
    const session = () => this.#session;
    // The `context` hook object, built at the bottom of this literal and asked
    // by the tool gate above. Declared here because the gate must ask the same
    // object the request path writes to: the in-memory fail-closed verdict of a
    // request gate that cannot be written lives in it.
    let contextHook = null;
    this.#hooks = {
      tool_call: async (event) => {
        // The gate answers only for a session that RESOLVES to this adapter.
        // Every session of one runner shares these handlers, and the registry is
        // the one authority on which adapter owns which session: if this event's
        // session is not registered to this object, its verdict is not ours to
        // give. Without this check the gate answered from whatever state this
        // object held — the leak that refused session `01a1958f` with a
        // sibling's `PREFIX_DRIFT` (2026-11-01).
        if (adapterForTool(this.#ctx) !== this) return unboundRefusal(event?.toolName ?? "this tool");
        // Fail closed: a session whose narrowing was refused must not run work
        // with an unverified tool face. A `context` hook cannot refuse a request
        // on this host — measured: its throw is logged as an extension error and
        // the request ships unchanged (see AGENTS.md §8, "实测的宿主事实"). So
        // the refusal is enforced here, on the first tool the model tries to
        // call. (`before_agent_start` is not covered by that measurement; it is
        // not relied on either way.)
        if (!this.#usable) {
          return {
            block: true,
            reason: `Herdchestra: this session's tool face could not be verified (${this.#narrowFailure ?? "not narrowed yet"}); refusing to run`,
          };
        }
        // No reading, no work. `roleOfAgent` answers `unproven` when the host has
        // not filled the session's `agent` yet, and an unproven session has no
        // face — so it must not run any of this adapter's tools either, whatever
        // the host still offers. Reading that absence as Manager is what let a
        // real session run with the Manager's face and ledger on a guess.
        if (this.#role === ROLE.UNPROVEN) {
          return {
            block: true,
            reason:
              "SESSION_ROLE_UNKNOWN: this session's role could not be read (no agent fact yet), so no face is granted",
          };
        }
        // The gate asks the object this adapter built, for ITS session: the
        // resolution above already refused an event that is not this session's,
        // so the verdict below can only be this session's own.
        return await createToolCallHandler({
          role: this.#role,
          scopeAdmit: (request) => session().scopeAdmit(request),
          // §2.3: the evaluation window is the session's, and the gate reads it
          // at call time so a review accepted earlier in this turn already
          // shuts the face.
          reviewWindow: this.reviewWindow,
          // §8.1 a: a request whose projection failed must not be acted on.
          // Answered from the hook's OWN gate object below, because the
          // in-memory fail-closed verdict lives there: a gate that could not be
          // written must refuse here too, not only in the durable file.
          requestRefusal: () => contextHook.requestRefusal(),
          // The request whose response issued this call: the last one that
          // SETTLED. OMP's `tool_call` event carries no request id, but a call
          // can only exist for a response whose request was projected and sent.
          requestId: () => session().requestInFlight(),
          // A refused call is the moment the model is stopped, and it leaves a
          // line of its own: the incident this covers had the stream refusing
          // calls while every durable file said `allowed: true`.
          refusalLog: ({ request, tool, reason }) => {
            const root = session().state.root;
            const sessionId = session().state.sessionId;
            const code = typeof reason === "string" ? reason.split(":")[0] : "CONTEXT_PROJECTION_FAILED";
            recordRequest(
              requestLogFile(root, sessionId),
              gateStateRecord({
                phase: "refusal",
                sessionId,
                host: session().state.hostVersion,
                verdict: { request, allowed: false, stage: "request", code, detail: null },
                trigger: typeof tool === "string" ? tool : null,
              }),
            );
          },
        })(event);
      },
      tool_result: createToolResultHandler({ scopeSettle: (event) => session().scopeSettle(event) }),
      before_agent_start: createBeforeAgentStartHandler({
        role: this.#role,
        onTurn: () => session().nextTurn(),
        // Spec §6: the system names the draft path to the model. Resolved per
        // turn, because the session id it is derived from is only stable once
        // the session exists.
        draft: this.#role === ROLE.PLAN
          ? () => ({
            path: session().ports().draft.path(),
            ...(session().state.draftDirFailure === null ? {} : { failure: session().state.draftDirFailure }),
          })
          : null,
        // Spec §2.3: every Manager request opens an evaluation round. Recorded
        // here, and narrowed only when the phase actually changes: §7.3 says
        // `setActiveTools` inside `before_agent_start` makes the host re-run
        // policy preparation, so it is paid once per round, not per request.
        // A worker never opens one: the window is the Manager's face only.
        onEvaluationStart: this.#role === ROLE.MANAGER ? () => this.openEvaluation() : null,
        // A deferred relay that could not run is stated to the model rather
        // than left as a silent no-op: the in-turn receipt said "deferred", and
        // nothing else would ever contradict it.
        relayFailure: () => session().state?.relayFailure ?? null,
      }),
      // Scope books per MODEL turn, which the host marks with `turn_start` and
      // `turn_end` (agent-loop.ts:1256 / :789). Closing on a tool result would
      // settle a turn before its other concurrent calls had arrived.
      turn_start: createTurnStartHandler({ scopeOpenTurn: (event) => session().scopeOpenTurn(event) }),
      turn_end: createTurnEndHandler({
        scopeCloseTurn: (event) => session().scopeCloseTurn(event),
        // The turn's ending message, kept for the idle boundary: an operator
        // interrupt is written there and nowhere else.
        noteTurnEnd: (message) => session().noteTurnEnd(message),
      }),
      // The idle boundary: where a relay deferred during a live turn is
      // performed. A worker never has one (it has no ledger and no relay port),
      // and `performDeferredRelay` answers null for a session with no pending
      // intent, so the hook costs one property read on every ordinary end.
      agent_end: createAgentEndHandler({
        performDeferredRelay: async (event) => {
          // The turn is over, so the tail abort a fission asked for runs HERE:
          // this is the one moment the adapter can interrupt the owner without
          // racing the host's own turn bookkeeping. A replaced present stops
          // producing turns from its mark onward; this is the turn in flight.
          session().control?.drainTurnEnd?.();
          const done = await session().performDeferredRelay(event);
          // 根 §2.2: a user-facing session's turn that ended without one of its
          // two legal exits is nudged, through the session's own queue, so the
          // work continues instead of stopping silently. `turnExit` books the
          // round once; a worker's end is its report and is never nudged.
          const exit = session().turnExit(event);
          // `booked` is what makes it once per turn: the host may re-emit
          // `agent_end` for the same turn, and a second nudge would read as the
          // system nagging about work the model is already doing.
          if (
            exit.booked === true &&
            exit.stopAllowed === false &&
            typeof exit.nudge === "string" &&
            exit.nudge.length > 0
          ) {
            await session().nudge(exit.nudge);
          }
          return done;
        },
      }),
      // Built once, and kept: the hook object owns the in-memory half of the
      // request gate, so the `tool_call` gate above asks the same object the
      // request path writes to.
      context: (contextHook = createContextHandler({
        scopeProject: (request) => session().scopeProject(request),
        // `barriered` says the copy came from the relay barrier: the Pair Hint
        // re-anchors its prefix chain there, which is the one legitimate break
        // (§2.4 第 3 步). Without it the successor's second request drifts.
        pairHintProject: (messages, options) => session().pairHintProject(messages, options),
        // The relay fence (spec §5.3.2 step 3): a successor's request copy
        // keeps only the operator's own input and the succession notice.
        // The fence governs the SUCCESSOR's requests. It is armed at the
        // transcript switch, not at the publish, so the turn that established
        // the relay keeps its own conversation (measured on 18.14.2: arming it
        // at the publish made the model lose its history and fork 116 times in
        // one turn).
        barrier: () => session().state.fence,
        // The durable verdict this request path writes before and after it
        // projects (see `createContextHandler`). `open` and `settle` answer
        // whether the write landed; `read` answers the verdict the file carries.
        // A gate that cannot be written cannot refuse later work through the
        // file, so the handler holds the refusal in memory and refuses every
        // call until a request settles a verdict the file really carries.
        // Every verdict is keyed by the REQUEST it decided (`session-state.mjs`
        // `requestRefusal`): a call is judged by the last SETTLED request, so a
        // projection still in flight for a side channel or for the next request
        // can never refuse a call that an already-projected request issued.
        requestGate: {
          open: (id) => session().state.openContextRequest(id),
          settle: (failure, id) => session().state.settleContextRequest(failure, id),
          read: () => session().state.requestRefusal(),
        },
        // A relay record exists but the transcript switch has not run: the
        // request must SAY so, or the model restarts from nothing (measured on
        // 18.14.2: 116 forks in one turn, never ending).
        relayOwed: () => session().state.relayPending?.record ?? null,
        // The durable request diagnostic (`drift-log.mjs`): one line per
        // `context` call, shapes and digests only. It is what turns "the pair
        // hint refused at index 1" into "the host finalised the message it was
        // answering" — the question the 2026-11-01 incident took a hand-read of
        // the host's session database to answer.
        // A side-channel turn (title/card/probe) is the host's own UI request,
        // not the operator's work: it must not settle the gate, and a failure in
        // it must not cut its copy. Measured on 18.14.2 (2026-11-01, session
        // 01a19547): the title request fired through this hook mid-turn, was
        // accepted as the chain's anchor, and the real request that followed was
        // refused with PREFIX_DRIFT — the session then lost every tool call.
        sideTurn: (messages) => isSideChannelRequest(messages),
        // `writeFailure` is set only on the retry, after the writer reported that
        // the line did not land in the session's own file: the record then names
        // the failure it carries, so a reader of the fallback file sees both the
        // request's verdict and why it is there.
        requestLog: ({ phase, incoming, presented, failure, writeFailure = null }) => {
          const root = session().state.root;
          const sessionId = session().state.sessionId;
          return recordRequest(
            requestLogFile(root, sessionId),
            {
              ...requestRecord({ phase, sessionId, host: session().state.hostVersion, incoming, presented, failure }),
              ...(writeFailure === null ? {} : { writeFailure }),
            },
          );
        },
        // Every change of the request gate's verdict, written at the moment it
        // happens. The request record above cannot cover this window: the gate
        // is opened before the request is projected and the host may end the
        // handler before the record is written, which is the state measured on
        // 2026-11-01 — tools refused with `CONTEXT_PROJECTION_FAILED`, every
        // gate file `allowed: true`, and not one `.requests.jsonl` line about
        // the refusal. The answer says whether the line landed; the handler
        // states a line that did not in the request it sends.
        onGate: ({ phase, verdict }) => {
          const root = session().state.root;
          const sessionId = session().state.sessionId;
          const outcome = recordRequest(
            requestLogFile(root, sessionId),
            gateStateRecord({ phase, sessionId, host: session().state.hostVersion, verdict }),
          );
          return outcome !== null && typeof outcome === "object" ? outcome.ok === true : outcome !== false;
        },
      })),
    };
    return this.#hooks;
  }

  // Register everything and narrow the visible tools once. A second call on the
  // same instance is the host's replayed `session_start` and does nothing.
  // Tools are registered by the factory (see `herdchestra`); this attaches the
  // session's hooks and narrows its visible set once.
  async installHooks() {
    if (this.#installed) return this;
    // A context handed to the constructor IS a binding (`#bound` is set there),
    // and its hooks are installed on first use like any other: a caller that
    // builds an adapter around one context — a worker build, a test harness, a
    // host path that hands the session over directly — must not be left with an
    // unwidened, un-narrowed face.
    if (this.#ctx !== null) this.bind(this.#ctx);
    if (!this.#bound) return this;
    // The narrowing is a host action, and a host refuses actions until its
    // extension runtime is initialized (`runner.initialize` runs from the
    // startup paths, `modes/runtime-init.ts:74`). An early event can therefore
    // throw "Extension runtime not initialized"; a later one succeeds.
    //
    // A refusal must NOT be swallowed and retried on the next event: the first
    // provider request would then go out with the session's full, unverified
    // tool face. So a failure marks the session unusable, and every entry point
    // that would start work checks `#usable` first and refuses — fail closed.
    try {
      await this.narrowTools();
    } catch (error) {
      this.#narrowFailure = String(error?.message ?? error);
      this.#usable = false;
      return this;
    }
    // An unproven role is not a usable session: the narrowing succeeded, but it
    // narrowed to no face at all, and every tool call will be refused by the
    // `SESSION_ROLE_UNKNOWN` gate. Saying so once, here, is what makes the
    // refusal visible instead of surfacing as a model that mysteriously cannot
    // call anything.
    if (this.#role === ROLE.UNPROVEN) {
      this.#narrowFailure = "SESSION_ROLE_UNKNOWN";
      this.#usable = false;
      return this;
    }
    this.#usable = true;
    this.#installed = true;
    return this;
  }

  // Whether this session may run work at all. False until the narrowing
  // succeeded, and false after a refused narrowing.
  get usable() {
    return this.#usable;
  }

  get narrowFailure() {
    return this.#narrowFailure;
  }

  // Kept for a host or a test that wants the old single entry point.
  async install() {
    for (const tool of this.tools()) this.#pi.registerTool(tool);
    return await this.installHooks();
  }

  // Spec §2.3: the Manager's read-only review face is open from a round's first
  // evaluation until that round's `review` is accepted.
  //
  // The round boundary is the host's own request boundary, not a counter of
  // ours: `beginEvaluation` runs from `before_agent_start`, which the host calls
  // once per request, so the face cannot drift open or shut by a turn we
  // miscounted. `endEvaluation` runs when a review is ACCEPTED — a refused
  // review leaves the round's evaluation open, because the Manager still has not
  // judged.
  //
  // Re-narrowing is safe here and nowhere else: spec §7.3 warns that calling
  // `setActiveTools` inside `before_agent_start` makes the host re-run policy
  // preparation, so `beginEvaluation` only records the phase and the narrowing
  // that the request path already performs picks it up.
  //
  // Answers whether the phase MOVED: `true` only on the call that opened the
  // round, `false` for a repeat. That is the fact `openEvaluation` narrows on,
  // so the visible set is written once per round and never once per request.
  beginEvaluation() {
    // The session's `ReviewWindow` is the authority (§2.3); `#evaluating`
    // mirrors it for the visible set. `beginRound()` is idempotent within one
    // round, so a host that prepares a request more than once does not reopen a
    // round a review just shut.
    this.#session.state?.window?.beginRound?.();
    if (this.#evaluating === true) return false;
    this.#evaluating = true;
    return true;
  }

  // Open this round's evaluation and make the face visible. §7.3 warns that
  // `setActiveTools` inside `before_agent_start` makes the host re-run policy
  // preparation (up to three times) and requires the system prompt to be
  // idempotent. This adapter's prompt IS a pure function of the role, so the
  // re-run is safe; it is still paid only when the phase actually changes,
  // which is once per round rather than once per request.
  async openEvaluation() {
    // §7.3: `setActiveTools` inside `before_agent_start` makes the host re-run
    // policy preparation, so it is paid ONLY when the phase really changed —
    // once per round, never once per request. `beginEvaluation` records the
    // round and answers whether the phase moved; a second preparation of the
    // same round answers `false` and nothing is written.
    const moved = this.beginEvaluation();
    if (moved !== true) return this.#evaluating;
    try {
      await this.narrowTools();
    } catch (error) {
      // A host that refuses the action must not fail the request: the face
      // stays as it was, which is the pre-existing behaviour.
      this.#narrowFailure = String(error?.message ?? error);
    }
    return this.#evaluating;
  }

  // Shut the round's evaluation. The answer is the phase AFTER the call, which
  // is what the review callback reads (`endEvaluation() !== false` means the
  // face is really gone); `beginEvaluation` answers the opposite question — did
  // the phase move — so the two are not interchangeable.
  endEvaluation() {
    if (this.#evaluating === false) return this.#evaluating;
    this.#evaluating = false;
    return this.#evaluating;
  }

  // The window itself, as the `tool_call` gate and the tests read it. A session
  // that has not started yet has none, and an absent window is a shut window:
  // nothing is evaluated before a session exists.
  get reviewWindow() {
    return this.#session.state?.window ?? null;
  }

  // Open means: this adapter opened the phase AND the session's window agrees.
  // The second half is what keeps a round the ledger already judged shut even
  // if a narrowing was missed.
  get evaluating() {
    return this.#evaluating === true && (this.reviewWindow === null || this.reviewWindow.open === true);
  }

  // Drop the replaced tools from this session's visible set. A read of the
  // current set keeps anything the user or another extension added.
  async narrowTools() {
    const current = typeof this.#pi.getActiveTools === "function" ? this.#pi.getActiveTools() : [];
    // The harness's own tool face is whatever the session offered before this
    // adapter narrowed it; those names survive unless the role forbids them.
    if (this.#baseline === null) this.#baseline = Object.freeze([...current]);
    const next = visibleToolsFor(this.#role, current, this.#baseline, { evaluating: this.#evaluating });
    await this.#pi.setActiveTools(next);
    // `HERDCHESTRA_DEBUG=1` prints the decision, so a real-host run shows which
    // role the session took and what the narrowing changed. Silent by default:
    // a working session must not write to its own transcript.
    if (process.env.HERDCHESTRA_DEBUG === "1") {
      const agent = this.#ctx?.agent ?? null;
      // `parentId` and `forked` decide whether a session may fission (§4.2), so
      // they are printed beside the role: a refused fission is then explained by
      // one line instead of a second run.
      console.log(
        "[herd] narrow",
        JSON.stringify({
          kind: agent?.kind ?? null,
          name: agent?.name ?? null,
          id: agent?.id ?? null,
          parentId: agent?.parentId ?? null,
          role: this.#role,
          forked: this.#forked,
          program: this.#session.name(),
        }),
      );
      console.log("[herd] active before", JSON.stringify(current));
      console.log("[herd] active after", JSON.stringify(next));
    }
    return next;
  }
}

// The factory OMP calls. OMP passes exactly one argument (`api`); the second
// parameter stays accepted for a test harness that injects a dispatcher, but the
// real host never supplies one. See the header.
//
// ## One adapter per SESSION, and tools that resolve their session at call time
//
// The factory runs before any session exists (`loader.ts:454`), so at that
// moment there is no role and no session id. Tools must still be registered
// there — the host snapshots the registered set while building the session
// (`sdk.ts:3618`) — and the hooks must be registered there too, because the
// first event of a session may arrive before anything else.
//
// What the factory must NOT do is decide which session those tools belong to.
// One adapter per runner meant the first session to bind owned every later
// session's tools, hooks and gate: on 2026-11-01, in one omp process, session
// `01a1958f` was refused with a SIBLING's `PREFIX_DRIFT` verdict while leaving
// no file of its own. So the split is:
//
//   - the FACTORY adapter registers the tool definitions and, on any event,
//     resolves or creates the adapter for THAT event's session and forwards to
//     it (below);
//   - every tool definition resolves the CALLING session's adapter per call
//     (`adapterForCall`), and a call it cannot resolve is refused
//     `SESSION_UNBOUND` — never served from another session.
//
// A session is identified by `sessionIdOf(ctx)`: the host's session id, which
// is also the name of every durable file the adapter writes. `/new` and
// `/switch` give a session a new id, so a switch creates a new adapter and
// leaves the old one's state exactly where it was.
export default function herdchestra(pi, options = {}) {
  const dispatch = typeof options?.dispatch === "function" ? options.dispatch : null;
  // The adapter that owns no session: it registers the tool definitions and
  // answers an event for a session it cannot name.
  const root = new HostAdapter({ pi, ctx: null, dispatch });
  for (const tool of root.tools()) pi.registerTool(tool);

  // The adapter for this event's session. A session whose id cannot be read at
  // all gets the root adapter (its hooks refuse by name, see `hooks()`); a
  // session this process has not seen gets its own.
  const adapterFor = (ctx) => {
    if (ctx === null || ctx === undefined) return root;
    const id = ctx?.sessionManager?.getSessionId?.();
    if (typeof id !== "string" || id.length === 0) return root;
    const existing = ADAPTERS.get(id);
    if (existing !== undefined) return existing;
    const adapter = new HostAdapter({ pi, ctx: null, dispatch });
    adapter.bind(ctx);
    return adapter;
  };
  // Each event gets exactly one handler from us, registered once. The handler
  // reads the adapter AFTER the event arrives: the same closure serves every
  // session of this runner, and the session is the event's, not the closure's.
  const hookFor = (name) => async (event, ctx) => {
    // The session id this event belongs to. A `tool_call` with none is refused
    // BEFORE any adapter is asked: falling through would ask whichever adapter
    // the event resolved to, and an id the registry does not hold resolves to
    // the factory's own adapter — whose gate is whatever the first session that
    // bound there put. That borrowed verdict is the leak this split exists to
    // end, and `SESSION_UNBOUND` is the same answer the tool definitions give,
    // so the model reads one fact in both paths.
    const id = ctx?.sessionManager?.getSessionId?.();
    if (name === "tool_call" && (typeof id !== "string" || id.length === 0)) {
      return unboundRefusal(event?.toolName ?? "this tool");
    }
    const adapter = adapterFor(ctx);
    await adapter.installHooks();
    const hook = adapter.hooks()[name];
    return typeof hook === "function" ? await hook(event) : undefined;
  };

  // `session_start` is also the lane's authorization handshake. A lane starts a
  // worker and asks: "did an adapter bind in that worker and narrow its face?"
  // The token is answered by the adapter of THAT session only, with the facts
  // it actually observed. `hasHandlers()` is not used: it reports that some
  // extension has a handler, which another extension can satisfy.
  pi.on("session_start", async (event, ctx) => {
    const handshake = event?.herdchestraHandshake;
    const adapter = adapterFor(ctx);
    await adapter.installHooks();
    const hook = adapter.hooks().session_start;
    if (typeof hook === "function") await hook(event);
    if (handshake === undefined || typeof handshake.reply !== "function") return undefined;
    handshake.reply({
      token: handshake.token,
      // The facts this adapter observed: it bound, and its narrowing landed.
      bound: adapter.bound,
      narrowed: adapter.usable,
      role: adapter.role,
      reason: adapter.narrowFailure ?? (adapter.usable ? null : "narrowing did not run"),
    });
    return undefined;
  });
  // The remaining hooks are registered once, at factory time, and resolve the
  // event's session on every call. `context` is first: the host calls it before
  // every provider request, including the first one of a session that never
  // emits `session_start`.
  pi.on("context", hookFor("context"));
  pi.on("before_agent_start", hookFor("before_agent_start"));
  pi.on("tool_call", hookFor("tool_call"));
  pi.on("tool_result", hookFor("tool_result"));
  pi.on("turn_start", hookFor("turn_start"));
  pi.on("turn_end", hookFor("turn_end"));
  // §2.1/§7.3: the parent-side identity gate. The host fires this ONCE per real
  // child dispatch (`task/structured-subagent.ts:492-521`), in the PARENT's
  // session, before the child resolves its model — so it is registered once
  // here, not per session, and it does not read the event's ctx. A child that
  // does not declare Coder or DevOps is refused by name; a declared one may
  // carry a model this adapter assigns (it assigns none, so `{}`).
  pi.on("before_subagent_spawn", createSubagentSpawnHandler());
  pi.on("agent_end", hookFor("agent_end"));
}

export { roleOfAgent, isWorkerRole };
