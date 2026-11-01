// The worker lane: one real OMP session created and driven by this adapter
// (spec §4.1, §7.3).
//
// ## Why the SDK, not the `task` tool
//
// An extension cannot run another tool. The factory receives only `api`
// (`loader.ts:454` `await factory(api)`), `ExtensionAPI` has no dispatcher, and
// `ctx.invokeTool` delegates only to a built-in of the SAME name as the calling
// tool (`extensions/types.ts:877`) — `fork` has no such built-in. So `fork`
// starts the session itself through the SDK the host injects as `api.pi`
// (`ExtensionAPI.pi` → the coding-agent module): `createAgentSession`
// (`sdk.ts:1771`) returns a real `AgentSession`, and that object is everything a
// lane needs.
//
// ## What a worker session must be given
//
//   taskDepth > 0          the host treats it as a subagent (`sdk.ts:2234`);
//                          without it the worker is a second top-level session
//   agentId/agentName      the identity the worker sees (`sdk.ts:3571`)
//   toolNames/customTools  the worker face, fixed AT CREATION. A session created
//                          through the SDK never gets its extension runtime
//                          initialized by the host (`runner.initialize` is
//                          called only from the startup paths,
//                          `modes/runtime-init.ts:74` and
//                          `acp-agent.ts:2980`), so `setActiveTools` inside it
//                          throws "Extension runtime not initialized" —
//                          measured. `wireWorkerRuntime` supplies the host's own
//                          action set before the handshake. `restrictToolNames`
//                          is not the answer either: it drops every registered
//                          extension tool (`sdk.ts:3618`).
//
// ## Authorization (fail closed)
//
// A worker that did not load THIS adapter would run with the host's default face
// and no gate, so `fork` verifies before the first turn and refuses otherwise.
// `hasHandlers()` is not evidence — it reports that some extension has a
// handler, which another extension satisfies (measured on the real host). The
// lane emits a `session_start` carrying a one-time token; only this adapter
// answers it, with the bind and narrow facts it observed. No answer, a wrong
// token, or `narrowed !== true` all refuse the worker.
//
// Nothing here is invented: a host without `api.pi.createAgentSession` reports
// `NO_SESSION_FACTORY` and starts no lane.

import { WORKER_RECORD_TYPES, workerResult } from "./worker-roster.mjs";

// The SDK entry the host injects. `api.pi` is the whole coding-agent module, so
// the factory is read from it rather than assumed.
//
// An `api` may also be a session the SDK already returned: a worker's own tools
// are built with the worker's session, and a session is what the host gives that
// worker in place of an `ExtensionAPI`. Such a session's factory is
// `session.pi.createAgentSession` (the coding-agent module it carries), so the
// worker's `fork` uses the same real factory — never a copy.
//
// An `api` may also be the SDK namespace ITSELF (`api.pi`), which is what a
// worker's builder receives: `workerSdk` wraps `api.pi`, not `api`. Reading only
// the two nested shapes refused every nested `fork` with `NO_SESSION_FACTORY`
// while the wrapper was sitting right on the object it was given (measured
// 2026-11-01: `workerSdk` is on `api.pi`, so the namespace is what `start` is
// handed as its `api`).
export const sessionFactoryOf = (api) => {
  const candidate =
    api?.pi?.createAgentSession ?? api?.session?.pi?.createAgentSession ?? api?.createAgentSession;
  return typeof candidate === "function" ? candidate : null;
};

// The `api` a worker session's own extension would receive.
//
// A worker started through the SDK never runs the host's loader, so nothing
// hands its adapter an `ExtensionAPI`; the lane installs the worker's tools on
// its session instead. Three members matter:
//
//   registerTool  the adapter registers its five tools;
//   pi            the SDK namespace — spec §4.1 gives `fork` to every worker,
//                 not only the Manager, so a worker's `fork` starts a real
//                 session through the same factory the host injected;
//   lanes         the worker's OWN roster (`LaneTable`). The factory comes from
//                 `pi`, but every session it creates must be started through
//                 the calling session's table: `LaneTable.start` builds the
//                 worker face, initializes the runtime, authorizes the worker
//                 and starts its first turn. A worker that used another
//                 session's table would register its child in a roster it does
//                 not own.
//
// Nothing is invented: a worker whose parent has no SDK reports
// `NO_SESSION_FACTORY`, and `lanes` is absent when there is no roster.
export const workerExtensionApi = (parentApi, lanes = null) => ({
  registerTool: (tool) => parentApi?.registerTool?.(tool),
  pi: workerSdk(parentApi?.pi ?? null, lanes),
  // A THUNK is "the roster is not known yet", never a roster. Storing it would
  // make the worker's adapter believe it owns a table it does not have, and
  // `RoleSession#open` would never open the worker's own (`surface.mjs:372`).
  lanes: typeof lanes === "function" ? null : lanes,
});

// The factory a session's SDK was built around. A session created through a
// worker's SDK carries that SDK, so a builder given `session.pi` must unwrap it
// before wrapping it again for the child — see `workerSdk`.
export const unwrappedPi = (pi) => (pi !== null && typeof pi === "object" && pi[UNWRAPPED] !== undefined ? pi[UNWRAPPED] : pi);

// The REAL factory behind an `api`, with every wrapper removed.
//
// `LaneTable.start` must never create a session through a wrapper that resolves
// back to the table it is starting from: the wrapper would call `start` again,
// and the `starting` guard would refuse the child. A wrapper is for callers
// OUTSIDE a table (a direct SDK call); a table always goes to the module itself.
//
// Each shape an `api` takes is unwrapped in the place the factory sits:
//   api.pi            an `ExtensionAPI`, or `workerExtensionApi`
//   api.session.pi    a session the SDK returned
//   api itself        the SDK namespace (`workerExtensionApi(...).pi`)
export const rawFactoryOf = (api) =>
  sessionFactoryOf({
    pi: unwrappedPi(api?.pi),
    session: api?.session === undefined ? undefined : { pi: unwrappedPi(api.session?.pi) },
    createAgentSession: unwrappedPi(api)?.createAgentSession,
  });

// The SDK namespace a worker sees: the parent's real factory, wrapped so every
// session it creates lands in the calling session's own roster.
// The mark a wrapped SDK carries, so wrapping it twice is a no-op rather than
// a loop: a worker's session holds its own wrapped SDK, and a builder that
// wraps the module it was given again would call itself forever.
const WRAPPED = Symbol.for("herdchestra.workerSdk");
const UNWRAPPED = Symbol.for("herdchestra.workerSdk.base");

export const workerSdk = (parentPi, lanes) => {
  if (parentPi === null || parentPi === undefined || lanes === null) return null;
  if (parentPi[WRAPPED] === true) return parentPi;
  // A THUNK that answers no roster right now (a session still being created)
  // must not be wrapped yet: the answer `null` is "the roster is not known
  // yet", not "there is no roster". `install` re-reads the thunk after the bind
  // and installs the wrapper then, so the SDK that finally sits on the session
  // answers the session's OWN table instead of carrying a null forever (that
  // is what made a worker's nested `fork` answer SESSION_CREATE_FAILED).
  // `lanes` is a TABLE or a THUNK. A table captured at construction belongs to
  // whoever built these tools — the session that STARTED the worker — and a
  // worker's `fork` would then create its child in the starter's roster
  // (measured 2026-11-01: the Manager's journal received `worker-fork:w1-child`
  // and its `join` claimed the worker's child). A thunk is read per call, so the
  // child lands in the roster of the session that is actually forking.
  return {
    ...parentPi,
    [WRAPPED]: true,
    // The factory this wrapper was built around. A session that carries this
    // SDK hands its own workers THIS, never the wrapper — otherwise wrapping a
    // session's SDK for its child would wrap the wrapper, and the first
    // `createAgentSession` would recurse until the stack ends.
    [UNWRAPPED]: parentPi,
    async createAgentSession(options = {}) {
      const table = typeof lanes === "function" ? lanes() : lanes;
      if (table === null || table === undefined) return undefined;
      // A table that is creating THIS session cannot be the one the session
      // forks into: that is the creator's roster, and a fork through it would
      // start the child inside the start it is already inside (measured: an
      // unbounded recursion). It means the session is being created and has no
      // roster of its own yet, so the honest answer is a refusal.
      //
      if (table.starting === true) return undefined;
      // The worker's `fork` goes through the lane table, which does the whole
      // job (face, runtime, authorization, first turn) and answers a receipt.
      // A caller that asked the SDK directly gets the started session, or
      // nothing when the start was refused — never a half-built session.
      // No default identity here: a session started without a role would run
      // on an invented one (§2.1). `start` refuses it by name.
      // The SDK's own option names are read here: a caller that names the
      // identity the way the host does (`agentName`) is not made to guess ours.
      const started = await table.start({
        ...options,
        name: options.name ?? options.agentId ?? null,
        role: options.role ?? options.agentName ?? null,
        brief: options.brief ?? "",
      });
      return started.ok === true ? { session: started.lane.session } : undefined;
    },
  };
};

// Whether this host can create a session, which is what `fork` needs.
export const hasSessionFactory = (api) => sessionFactoryOf(api) !== null;

// The authorization handshake key. A lane puts `{ token, reply }` here; only
// this adapter answers it.
export const HANDSHAKE = "herdchestraHandshake";

// The parameters of one worker start. A lane is created through the SDK, so
// there is no `task` payload; kept for readers and tests.
export const laneStart = ({ name, brief, agent }) => ({ name, brief, agent });


// Wire a worker session's extension runtime exactly as the host wires its own
// sessions (`modes/runtime-init.ts` `initializeExtensions`, the shared print/RPC
// path, and `modes/acp/acp-agent.ts` `#configureExtensions`). A session created
// through the SDK gets throwing stubs instead, so every action fails until this
// runs — that is why a worker's `session_start` narrowed nothing before.
//
// Every action forwards to the session's own API. A session that lacks one of
// the members below is NOT wired with a placeholder: `wireWorkerRuntime` reports
// the missing member and the lane refuses the worker (`RUNTIME_INIT_FAILED`).
// An action that answers `false` without asking the session would tell the
// worker's adapter that work happened when it did not.
//
// The host's own hooks are untouched: Herdchestra registers no command and no
// session-change command, so `getCommands` lists exactly what the host's own
// action would list for this session.

// `USER_INTERRUPT_LABEL` (`packages/tui/src/chat/messages.ts:101`). It rides the
// abort controller and is what marks a deliberate interrupt in the transcript,
// the same value the host's own action passes (`runtime-init.ts`).
export const USER_INTERRUPT_LABEL = "Interrupted by user";

// `getSessionSlashCommands` (`extensibility/extensions/get-commands-handler.ts`)
// over the members of a session: the runner's own commands, the loaded prompt
// commands and the skill commands. Built-in slash commands are excluded by the
// host too — `getCommands` is the surface of commands an extension did not
// register itself.
const sessionCommands = (session) => {
  const out = [];
  const runner = session.extensionRunner;
  if (runner !== null && typeof runner?.getRegisteredCommands === "function") {
    for (const command of runner.getRegisteredCommands([])) {
      out.push({ name: command.name, description: command.description, source: "extension" });
    }
  }
  for (const custom of session.customCommands ?? []) {
    out.push({
      name: custom.command.name,
      description: custom.command.description,
      source: "prompt",
      path: custom.resolvedPath,
    });
  }
  if (session.skillsSettings?.enableSkillCommands === true) {
    for (const skill of session.skills ?? []) {
      out.push({
        name: `skill:${skill.name}`,
        description: skill.description || undefined,
        source: "skill",
        path: skill.filePath,
      });
    }
  }
  return out;
};

// The members every action below forwards to. A name is a dotted path into the
// session; the first missing one is what `wireWorkerRuntime` reports.
const REQUIRED_SESSION_MEMBERS = Object.freeze([
  "sendCustomMessage",
  "sendUserMessage",
  "sessionManager.appendCustomEntry",
  "sessionManager.appendLabelChange",
  "sessionManager.getSessionName",
  "sessionManager.setSessionName",
  "getEnabledToolNames",
  "getAllToolInfos",
  "setActiveToolsByName",
  "modelRegistry.getApiKey",
  "setModel",
  "thinkingLevel",
  "setThinkingLevel",
  "serviceTierByFamily",
  "setServiceTierFamily",
  "model",
  "isStreaming",
  "abort",
  "queuedMessageCount",
  "getContextUsage",
  "systemPrompt",
  "runEphemeralTurn",
  "compact",
  "waitForIdle",
  "newSession",
  "branch",
  "navigateTree",
  "switchSession",
  "reload",
]);

const memberOf = (session, dotted) => {
  let value = session;
  for (const part of dotted.split(".")) {
    if (value === null || value === undefined) return undefined;
    value = value[part];
  }
  return value;
};

// The first required member this session does not have, or null.
export const missingWorkerMember = (session) => {
  for (const name of REQUIRED_SESSION_MEMBERS) {
    const value = memberOf(session, name);
    if (value === undefined || value === null) return name;
  }
  return null;
};

// The actions `runner.initialize` receives. `trackSend` observes every send the
// way the host's own wiring does, so a send that fails is reported instead of
// becoming an unhandled rejection inside the worker.
export const workerActions = (session, { trackSend = () => {} } = {}) => ({
  sendMessage: (message, options) => {
    const task = session.sendCustomMessage(message, options);
    trackSend(task);
    return task;
  },
  sendUserMessage: (content, options) => {
    const task = session.sendUserMessage(content, options);
    trackSend(task);
    return task;
  },
  appendEntry: (customType, data) => {
    session.sessionManager.appendCustomEntry(customType, data);
  },
  setLabel: (targetId, label) => {
    session.sessionManager.appendLabelChange(targetId, label);
  },
  getActiveTools: () => session.getEnabledToolNames(),
  getAllTools: () => session.getAllToolInfos(),
  setActiveTools: (toolNames) => session.setActiveToolsByName(toolNames),
  getCommands: () => sessionCommands(session),
  setModel: async (model) => {
    const apiKey = await session.modelRegistry.getApiKey(model);
    if (!apiKey) return false;
    await session.setModel(model);
    return true;
  },
  getThinkingLevel: () => session.thinkingLevel,
  setThinkingLevel: (level, persist) => session.setThinkingLevel(level, persist),
  getServiceTiers: () => session.serviceTierByFamily,
  setServiceTier: (family, tier) => session.setServiceTierFamily(family, tier),
  getSessionName: () => session.sessionManager.getSessionName(),
  setSessionName: async (name) => {
    await session.sessionManager.setSessionName(name, "user");
  },
});

// The context actions `runner.initialize` receives.
export const workerContextActions = (session) => ({
  getModel: () => session.model,
  isIdle: () => !session.isStreaming,
  abort: () => {
    // `abort` is async on the session; the action is synchronous in the host
    // contract, so the rejection is observed here rather than dropped.
    void Promise.resolve(session.abort({ reason: USER_INTERRUPT_LABEL })).catch(() => {});
  },
  hasPendingMessages: () => session.queuedMessageCount > 0,
  shutdown: () => {},
  getContextUsage: () => session.getContextUsage(),
  getSystemPrompt: () => session.systemPrompt,
  runEphemeralTurn: (args) => session.runEphemeralTurn(args),
  compact: (instructionsOrOptions) => {
    const instructions = typeof instructionsOrOptions === "string" ? instructionsOrOptions : undefined;
    const options = instructionsOrOptions !== null && typeof instructionsOrOptions === "object"
      ? instructionsOrOptions
      : undefined;
    return session.compact(instructions, options);
  },
});

// The command context actions. The host wraps session changes in its own
// quiesce/reattach (`wrapSessionChange`); an extension has no such wrapper, so
// these forward to the session's own transition and report what it returned.
export const workerCommandActions = (session) => ({
  getContextUsage: () => session.getContextUsage(),
  waitForIdle: () => session.waitForIdle(),
  newSession: async (options) => {
    const success = await session.newSession({ parentSession: options?.parentSession });
    if (success && options?.setup) await options.setup(session.sessionManager);
    return { cancelled: !success };
  },
  branch: async (entryId) => {
    const result = await session.branch(entryId);
    return { cancelled: result.cancelled };
  },
  navigateTree: async (targetId, options) => {
    const result = await session.navigateTree(targetId, { summarize: options?.summarize });
    return { cancelled: result.cancelled };
  },
  switchSession: async (sessionPath) => {
    const success = await session.switchSession(sessionPath);
    return { cancelled: !success };
  },
  reload: async () => {
    await session.reload();
  },
  compact: (instructionsOrOptions) => workerContextActions(session).compact(instructionsOrOptions),
});

// Wire a worker's runner with the real actions. Returns `{ ok: false, reason }`
// when the session lacks a member the host's own wiring uses; the lane then
// disposes the session and refuses the worker.
export const wireWorkerRuntime = (runner, session, { trackSend } = {}) => {
  if (runner === null || runner === undefined || typeof runner.initialize !== "function") {
    return { ok: false, reason: "the session exposes no extension runner to initialize" };
  }
  const missing = missingWorkerMember(session);
  if (missing !== null) {
    return { ok: false, reason: `the worker session has no ${missing} to wire its actions to` };
  }
  runner.initialize(
    workerActions(session, { trackSend }),
    workerContextActions(session),
    workerCommandActions(session),
  );
  return { ok: true };
};

// One lane's own view of what happened.
export class Lane {
  #name;
  #role;
  #session;
  #release;
  #agentId;
  #text;
  #settled;
  #resolveEnd;
  #unsubscribe;
  #ended;
  #startedAt;
  #turn;
  #turnError;
  #submitted;
  #settleSubmitted;
  // The end promise a roster has already armed a settle tail for. It is the
  // TURN's identity: `start` replaces `ended`, so a new turn is a new watch.
  watchedTurn = null;
  // Called synchronously the instant a turn ends, before the host's emit
  // returns. The roster writes its journal from here, so a reload on the very
  // next line already sees the settled worker.
  #onSettled = null;

  // `observeFrom` names an event this lane was NOT alive for. A table rebuilt
  // over a running lane adopts it (`LaneTable.adopt`): the host's events go to
  // the live lane, and replaying them here would count one turn end twice. So
  // an adopted lane starts listening at that event and ignores everything the
  // session emitted before it.
  constructor({ name, role, session, agentId, clock, release = null, observeFrom = null }) {
    this.#name = name;
    this.#role = role;
    this.#session = session;
    this.#release = typeof release === "function" ? release : null;
    this.#agentId = agentId;
    this.#text = "";
    this.#settled = false;
    this.#startedAt = clock();
    this.#ended = new Promise((resolve) => {
      this.#resolveEnd = resolve;
    });
    let watching = observeFrom === null;
    this.#unsubscribe = session.subscribe((event) => {
      if (!watching) {
        if (event !== observeFrom) return;
        watching = true;
        return;
      }
      this.#observe(event);
    });
  }

  get name() {
    return this.#name;
  }

  // Install the roster's synchronous settle sink. One sink per lane: the
  // table that owns the lane is the only one that writes its journal.
  onSettled(fn) {
    this.#onSettled = typeof fn === "function" ? fn : null;
    return this;
  }

  get role() {
    return this.#role;
  }

  get agentId() {
    return this.#agentId;
  }

  get session() {
    return this.#session;
  }

  get ended() {
    return this.#ended;
  }

  get settled() {
    return this.#settled;
  }

  get startedAt() {
    return this.#startedAt;
  }

  // The assistant text of the turn, as it stands. A finished lane's report and
  // the roster's own record both read it here, so the two cannot disagree.
  get text() {
    return this.#text;
  }

  // Resolves when the turn is live (host `agent_start`), or when it failed
  // before starting. It never waits for the turn to finish.
  get submitted() {
    return this.#submitted;
  }

  // A finished turn. Its result is in the roster's queue, written there by the
  // settle that ended it — the queue is the one place a result lives, so there
  // is nothing left to "take" from the lane.
  get finishedTurn() {
    return this.#settled;
  }

  // Only assistant text is the lane's report.
  #observe(event) {
    if (event === null || typeof event !== "object") return;
    if (event.type === "agent_start") {
      // The host began the turn. `prompt()` resolves only when the whole turn is
      // over (`agent-session.ts:9032 await run`), so waiting on it would make
      // `fork` block for the worker's entire run (measured: 4.9 s).
      this.#settleSubmitted({ ok: true });
      return;
    }
    if (event.type === "message_end" && event.message?.role === "assistant") {
      const content = event.message.content;
      const text = typeof content === "string"
        ? content
        : Array.isArray(content)
          ? content.filter((part) => part?.type === "text" && typeof part.text === "string").map((part) => part.text).join("")
          : "";
      if (text.length > 0) this.#text = text;
      return;
    }
    if (event.type !== "agent_end") return;
    // A non-terminal end is a continuation (retry, queued work, background job).
    if (event.isTerminal === false) return;
    if (this.#settled) return;
    this.#finish({ name: this.#name, agentId: this.#agentId, text: this.#text });
  }

  // The turn is over. The roster hears about it HERE, synchronously, before
  // the host's own emit returns: the journal is what a reload reads, and a
  // settle that waited for a microtask would be missing from disk when the
  // very next line reloads the roster — or lost entirely if the process dies
  // right after the turn.
  #finish(ended) {
    if (this.#settled) return false;
    this.#settled = true;
    this.#resolveEnd(ended);
    this.#onSettled?.(ended);
    return true;
  }

  // Start a turn WITHOUT waiting for it.
  start(brief) {
    this.#turnError = null;
    // A lane whose turn already ended can start another one (§4.1: a finished
    // worker's next brief continues in the same session). The END is a fact of
    // the TURN, not of the lane, so each start gets its own promise: reusing
    // the settled one made the next `settleTail` observe an end that had
    // already happened and record the old answer as the new turn's result.
    this.#settled = false;
    // A new turn has its own report. Without this, a continuation that ends
    // without new assistant text reports the PREVIOUS turn's text.
    this.#text = "";
    this.#ended = new Promise((resolve) => {
      this.#resolveEnd = resolve;
    });
    this.#submitted = new Promise((resolve) => {
      this.#settleSubmitted = resolve;
    });
    const turn = this.#session.prompt(brief);
    this.#turn = Promise.resolve(turn)
      .then((value) => {
        this.#settleSubmitted({ ok: true });
        return value;
      })
      .catch((error) => {
        this.#turnError = { name: error?.name ?? "Error", message: String(error?.message ?? error) };
        this.#settleSubmitted({ ok: false, error: this.#turnError });
        this.#finish({ name: this.#name, agentId: this.#agentId, text: this.#text, error: this.#turnError });
        return false;
      });
    return this;
  }

  async steer(brief) {
    // The branch is the LANE's own turn, never the host's `isStreaming`.
    //
    // The host flag is true for the whole in-flight bracket of a prompt, which
    // outlives the turn's terminal `agent_end` (HACK.md: `#promptInFlightCount`
    // is decremented after the bracket, and `agent_end` is emitted inside it).
    // So `isStreaming` is true in two windows where the lane's own state says
    // otherwise: after `start()` and before the host accepts the prompt, and
    // after the turn ended but before the bracket closed.
    //
    //   - unsettled turn: the brief joins the turn already running. A second
    //     `start()` here would replace `#ended` and send a second prompt whose
    //     own `agent_end` may never come (a prompt accepted while the agent is
    //     busy is queued, and the queue's turn end is the one the first tail
    //     already watches), leaving the lane unsettled forever;
    //   - settled turn: the brief starts the NEXT turn in the same session
    //     (§4.1), even while the host still reports streaming — `start()` is
    //     what gives that turn its own end promise and its own terminal event.
    if (!this.#settled) {
      await this.#session.steer(brief);
      return true;
    }
    this.start(brief);
    return true;
  }

  report() {
    return {
      ok: true,
      name: this.#name,
      agentId: this.#agentId,
      role: this.#role,
      text: this.#text,
      startedAt: this.#startedAt,
    };
  }

  // Take over a turn that is already running in this session.
  //
  // A table rebuilt over a live lane (a reload inside one process, a second
  // world in a test) does not own the lane the host's events reach, so it makes
  // its OWN lane over the same session and waits for the turn to end — which is
  // the only fact `join` needs. The turn's own progress is not replayed: the
  // event that ends it is the one this lane starts listening at.
  //
  // `observeFrom` is the session's most recent event, when the caller has one:
  // everything up to and including it happened before this lane existed.
  adopt({ observeFrom = null } = {}) {
    this.#unsubscribe?.();
    this.#unsubscribe = null;
    let watching = observeFrom === null;
    this.#unsubscribe = this.#session.subscribe((event) => {
      if (!watching) {
        if (event !== observeFrom) return;
        watching = true;
        return;
      }
      this.#observe(event);
    });
    return this;
  }

  // Stop listening to the session WITHOUT disposing it. A table a rebuild
  // replaces detaches its lanes (`LaneTable.detach`): the successor owns the
  // session now, and two live lanes over one session would hear the same turn
  // end and report it twice.
  detach() {
    try {
      this.#unsubscribe?.();
    } catch {}
    this.#unsubscribe = null;
    return this;
  }

  async dispose() {
    try {
      this.#unsubscribe?.();
    } catch {}
    this.#unsubscribe = null;
    try {
      await this.#session.dispose();
    } finally {
      // A disposed worker leaves no adapter registered: a later call can never
      // resolve to a session that no longer runs.
      this.#release?.();
      this.#release = null;
    }
  }
}

// The worker table a session uses. `fork` starts a real session through the SDK;
// the host's `task` tool is NOT reachable from an extension, so a lane never goes
// through it.
export class LaneTable {
  #lanes;
  #clock;
  #api;
  #extensionPaths;
  #parentAgentId;
  #taskDepth;
  #workerToolsFor;
  #workerToolNames;
  #toolNamesFor;
  #roster;
  #restored;
  #arrivals;
  #arriving;
  #generation;
  #history;
  #unclaimed;
  // Names whose result this roster already handed to a caller. A name is not a
  // turn: the worker stays addressable (`names`), but its old answer is never
  // offered twice, and a reload reads the same fact back from the journal.
  #handedOver;
  #onArrive;
  #runningSession;
  // Set once this roster has been torn down. A `join` that is waiting on it is
  // woken and answers a refusal naming this fact: a session that no longer
  // exists cannot produce the turn the wait was armed for, so leaving the wait
  // pending would hang the caller for the life of the process.
  #disposed;
  // Set when a rebuild replaced this table (`detach`). Its lanes stop listening,
  // so a `join` waiting here is released with the disposal-style refusal.
  #detached;
  // The workers this roster held when it was torn down. The teardown empties
  // the table, so without this the refusal could not say what the caller was
  // waiting for — and "you were waiting for w1" is the only useful part of it.
  #disposedNames;

  // `extensionPaths` are the extension roots every worker session must load.
  // `workerToolsFor(workerPi, lanes)` builds the worker face for ONE worker, after that
  // worker's session exists. It must be a factory, not a list: a tool closure
  // reads the session that owns it, so reusing another session's tools would
  // make the worker act on that session's registry, ledger and parks.
  // `workerToolNames` is the name set the host activates at creation.
  // `toolNamesFor(role)` overrides that set per lane. A lane is not always a
  // worker: a Plan hand-off starts a MANAGER session the same way `fork` starts
  // a worker (spec §6.1), and that session must be created with the Manager's
  // face, never the worker's.
  //
  // `roster` is the session's durable worker journal (`worker-roster.mjs`). A
  // table built without one is the in-memory roster of one process; a table
  // built WITH one starts from what the previous process wrote, so a reloaded
  // session still owes the results its workers produced while nobody was
  // listening. `onArrive(result)` is called once per finished, unclaimed
  // worker; it is how a host that tracks running work hears that this one
  // stopped.
  constructor({
    api,
    clock = () => Date.now(),
    extensionPaths = [],
    parentAgentId = null,
    taskDepth = 1,
    workerToolsFor = null,
    workerToolNames = [],
    toolNamesFor = null,
    roster = null,
    onArrive = null,
    // `runningSession(name)` answers the live session of a worker this process
    // still runs under that name, or null. It is what lets a roster restored
    // from the journal hand a running worker back to a lane instead of waiting
    // for a `settle` that belongs to the process which ended.
    runningSession = null,
  }) {
    this.#lanes = new Map();
    this.#clock = clock;
    this.#api = api;
    this.#extensionPaths = Object.freeze([...extensionPaths]);
    this.#parentAgentId = parentAgentId;
    this.#taskDepth = taskDepth;
    this.#workerToolsFor = typeof workerToolsFor === "function" ? workerToolsFor : null;
    this.#workerToolNames = Object.freeze([...workerToolNames]);
    this.#toolNamesFor = typeof toolNamesFor === "function" ? toolNamesFor : null;
    this.#roster = roster ?? null;
    // Waiters of `join`, and the promise one arrival resolves. `join` waits on
    // this, never on a snapshot of the roster taken when it was called.
    this.#arrivals = [];
    this.#arriving = null;
    // How many times the roster changed. A wait that answers without a change
    // taught `join` nothing, and re-arming on it would spin.
    this.#generation = 0;
    // What each lane ended with, by name, for a result that is rebuilt after a
    // reload of THIS process's table (the journal keeps identity and outcome;
    // the text is the lane's own).
    this.#history = new Map();
    this.#onArrive = typeof onArrive === "function" ? onArrive : null;
    this.#runningSession = typeof runningSession === "function" ? runningSession : null;
    this.#unclaimed = [];
    // Names whose result a claim already took. A claim is per RESULT: it never
    // suppresses a later turn of the same name, which is why the queue (not
    // this set) decides what is owed.
    this.#handedOver = new Set(this.#roster?.restored?.claimed ?? []);
    this.#disposed = false;
    this.#detached = false;
    this.#disposedNames = null;
    this.#restored = this.#adoptRestored();
    this.#adoptRunning();
    this.#settleRestored();
  }

  // A restored worker the journal says FINISHED, with no result in the journal's
  // own queue, is still owed to a caller: the record is the result. It is
  // queued here so `join` answers it, and only here — a result the journal
  // already queued is left alone, so nothing is reported twice.
  // The journal is the only source of results. Every turn that ended wrote its
  // SETTLE record synchronously (`Lane#finish` → `#settle` → `#record`), so the
  // replay's `unclaimed` list already holds exactly the turns nobody claimed, in
  // settle order. This queues that list verbatim — it never derives a result
  // from an entry, which would add a second copy of a turn the journal has.
  #settleRestored() {
    for (const result of this.#restored.unclaimed) {
      this.#unclaimed.push(workerResult({ ...result, restored: true }));
    }
  }

  // A restored worker that this process still runs is handed back to a lane
  // straight away, at construction: the roster is rebuilt before anything can
  // `join`, so there is no window in which `join` waits on a `settle` that no
  // live code will ever send.
  #adoptRunning() {
    if (this.#runningSession === null) return;
    for (const entry of [...this.#restored.entries]) {
      if (entry.state !== "running") continue;
      const session = this.#runningSession(entry.name);
      if (session === null || session === undefined) continue;
      this.adopt(entry.name, { session });
    }
  }

  // The entries a restored journal described, in the shape `join` reports: a
  // finished result nobody collected, or a worker that is still running.
  //
  // A name this table already holds a lane for is NOT adopted: a rebuilt table
  // over a running lane (a reload inside one process, a test's second world)
  // waits on that lane's real turn, and a finished lane's own report is the one
  // `join` must hand over. Adopting both would report the same worker twice.
  //
  // A worker this process cannot drive — its session belonged to a process that
  // is gone — stays an entry `join` waits on until the host settles it
  // (`settle`). It is never dropped on restore: dropping it is exactly the
  // "nothing to collect" lie this journal exists to prevent.
  #adoptRestored() {
    const restored = this.#roster?.restored ?? { entries: [], unclaimed: [] };
    const entries = [];
    const unclaimed = [];
    for (const entry of Array.isArray(restored.entries) ? restored.entries : []) {
      if (this.#lanes.get(entry.name) !== undefined) continue;
      entries.push({ ...entry, restored: true });
    }
    for (const result of Array.isArray(restored.unclaimed) ? restored.unclaimed : []) {
      if (this.#lanes.get(result.name) !== undefined) continue;
      unclaimed.push(result);
    }
    return { entries, unclaimed };
  }

  // Wake every waiter. One wake per arrival is enough: each waiter re-checks
  // the roster and re-arms if nothing is claimable, so a wake two waiters share
  // costs one extra check, never a lost arrival.
  #wake() {
    this.#generation += 1;
    const waiters = this.#arrivals;
    this.#arrivals = [];
    for (const wake of waiters) wake();
  }

  // The promise one arrival resolves, or the current one. Nothing polls.
  #nextArrival() {
    if (this.#arriving === null) {
      this.#arriving = new Promise((resolve) => {
        this.#arrivals.push(() => {
          this.#arriving = null;
          resolve();
        });
      });
    }
    return this.#arriving;
  }

  // How many times this roster changed since it was built.
  get generation() {
    return this.#generation;
  }

  // Whether this roster has been torn down. A `join` reads it after every wait:
  // a disposed session never produces the turn the wait was armed for, so the
  // wait must end with a refusal that names this fact.
  get disposed() {
    return this.#disposed === true;
  }

  // The workers this roster held when it was torn down, or null while it is
  // still live. A `join` that was released by the teardown names them, so the
  // caller can see what it was waiting for rather than an empty list that reads
  // like a roster which never held anything.
  get disposedNames() {
    return this.#disposedNames === null ? null : [...this.#disposedNames];
  }

  // Whether this roster has been detached from its workers' sessions by a
  // rebuild that replaced it. The lanes keep their sessions (the successor
  // drives them through `runningSession`), but they no longer hear turn ends,
  // so a `join` waiting here can never be answered. It ends with the same
  // refusal a teardown gives: from this table's point of view the work is gone.
  get detached() {
    return this.#detached === true;
  }

  // Release this table on a rebuild that replaces it: stop every lane listening
  // to its session and wake the waiters. The sessions are NOT disposed — the
  // successor adopts them through `runningSession` and re-arms its own tail.
  detach() {
    if (this.#disposed === true) return this;
    this.#detached = true;
    this.#disposedNames = this.names();
    for (const lane of this.all()) lane.detach();
    this.#wake();
    return this;
  }

  // Write one roster record. A failed append is reported by the roster itself
  // (`lastWriteFailure`); the table keeps working from memory, so a disk that
  // cannot take the record never turns a started worker into a refusal.
  #record(entry) {
    this.#roster?.record(entry);
  }

  // One lane's turn ended. Its result becomes claimable, the journal learns it,
  // and any waiting `join` is woken.
  //
  // Every turn end PUSHES its own result, in settle order. A result is per TURN
  // and is never replaced or withdrawn: a finished turn whose report nobody
  // joined is work the worker did and the Manager has not seen, so a later turn
  // of the same name queues behind it (§4.1: `join` returns the record of
  // whoever came back, and each turn comes back once).
  #settle(lane, { error = null } = {}) {
    const name = lane.name;
    this.#history.set(name, { text: lane.text, startedAt: lane.startedAt, error });
    // The lane's own last assistant text is the report: the ONE shape a finished
    // worker answers with, and the one the journal records.
    const result = workerResult({
      name,
      agentId: lane.agentId,
      role: lane.role,
      text: lane.text,
      startedAt: lane.startedAt,
      error,
    });
    this.#unclaimed.push(result);
    // The turn's own answer travels with the record. It is what makes a result
    // recovered after a reload the SAME result the process would have handed
    // over, instead of a name with no words in it.
    this.#record({ type: WORKER_RECORD_TYPES.SETTLE, name, agentId: lane.agentId, text: result.text, error });
    // A restored entry for this name is superseded by the live lane.
    this.#restored.entries = this.#restored.entries.filter((entry) => entry.name !== name);
    this.#onArrive?.(result);
    this.#wake();
  }

  // Record this lane's turn end, once. `Lane` reports a finished turn on its
  // `ended` promise; this is the one place the roster hears about it, so a
  // reload and a live `join` cannot disagree about whether a worker came back.
  //
  // The tail is armed for the turn the lane holds NOW, and arming it twice for
  // the same turn is a no-op: `Lane.start` replaces `ended` with a fresh
  // promise, so the same promise means the same turn. That is what lets every
  // caller (a start, a continuation, a steer, an adoption) ask for the tail
  // without knowing whether another caller already did.
  #settleTail(lane) {
    const name = lane.name;
    const turn = lane.ended;
    if (lane.watchedTurn === turn) return false;
    lane.watchedTurn = turn;
    // The journal is written from inside the turn's own end (`Lane#finish`),
    // synchronously: a reload on the very next line of the host's code must
    // already see this worker settled.
    lane.onSettled((ended) => {
      if (this.#lanes.get(name) !== lane) return;
      this.#settle(lane, { error: ended?.error ?? null });
    });
    // A turn that already ended is NOT drained here: its SETTLE record is in the
    // journal, and the queue was seeded from the journal's own `unclaimed` list
    // (`#settleRestored`). Creating a result from lane state for a settled turn
    // would queue the same turn twice. A tail is armed for an UNSETTLED turn
    // only, and a settled lane gets no tail at all.
    return true;
  }

  // The face a lane of this role is created with.
  #faceFor(role) {
    if (this.#toolNamesFor === null) return this.#workerToolNames;
    const names = this.#toolNamesFor(role);
    return Array.isArray(names) ? Object.freeze([...names]) : this.#workerToolNames;
  }

  get size() {
    return this.#lanes.size;
  }

  get(name) {
    return this.#lanes.get(name) ?? null;
  }

  // The lane for a name, running or finished: a finished lane is a durable
  // worker whose next brief continues in the same session (spec §4.1).
  live(name) {
    return this.#lanes.get(name) ?? null;
  }

  running(name) {
    const lane = this.#lanes.get(name);
    return lane !== undefined && !lane.settled ? lane : null;
  }

  all() {
    return [...this.#lanes.values()];
  }

  // Lanes `join` can still wait on: a running turn, or a finished turn whose
  // report has not been taken.
  // The lanes still running a turn. A settled lane is NOT pending: its result
  // is already in the queue, written there by the settle that ended it.
  pending() {
    return this.all().filter((lane) => !lane.settled);
  }

  names() {
    const names = new Set(this.#lanes.keys());
    for (const entry of this.#restored.entries) names.add(entry.name);
    // A name whose result was already handed over is still a worker this
    // session knows: a later brief continues it. Listing it is what lets a
    // caller tell "already collected" from "another roster".
    for (const name of this.#handedOver) names.add(name);
    return [...names];
  }

  // The workers the journal restored that this table has no lane for. They are
  // still running as far as this roster knows, and `adopt` is how a live one
  // comes back under a lane.
  restoredEntries() {
    return [...this.#restored.entries];
  }

  // Whether this roster still owes a `join` something, and whether `takeReady`
  // can hand one over. The two must agree: `join` waits while `owed()` is true,
  // so anything owed that `takeReady` cannot answer would wait forever.
  //
  // Owed is therefore exactly: a lane with a live turn, a restored worker the
  // journal says is still RUNNING (a turn that can still end, which the host
  // settles through `settle`), or a result nobody claimed.
  //
  // A restored entry that is FINISHED and whose claim is in the journal is NOT
  // owed: the claim was durably made, and each result is returned once (§4.1).
  // The journal already leaves such a result out of `unclaimed`
  // (`worker-roster.mjs` `replayRoster`), so the entry alone must not keep the
  // roster owing something no `join` could ever collect.
  owed() {
    return (
      this.pending().length > 0 ||
      this.#unclaimed.length > 0 ||
      this.#restored.entries.some((entry) => entry.state === "running")
    );
  }

  // The oldest unclaimed result, taken out of the queue. Arrival order is the
  // order the turns ended, which is what "first to come back" means (§4.1).
  //
  // A claim is written to the journal BEFORE it is answered: a reload after a
  // result was handed over must not offer it a second time.
  claim() {
    const [first] = this.#unclaimed;
    if (first === undefined) return null;
    this.#unclaimed = this.#unclaimed.slice(1);
    // The restored entry is the same fact as the queued result: a worker the
    // journal says finished. Once the result is handed over, the roster owes
    // nothing for that name, and keeping the entry would make `owed()` true
    // forever.
    this.#restored.entries = this.#restored.entries.filter((entry) => entry.name !== first.name);
    // The name still exists (a later brief continues that worker), so `names`
    // keeps listing it. The set is the roster's memory of "this name was
    // offered before", which a reload reads back from the journal.
    this.#handedOver.add(first.name);
    this.#record({ type: WORKER_RECORD_TYPES.CLAIM, name: first.name, agentId: first.agentId });
    this.#generation += 1;
    return first;
  }

  // What `join` would answer right now, or null when nothing is claimable.
  //
  // The queue is the order the turns ENDED, which is what "first to come back"
  // means (§4.1), so it is the only thing consulted here. Every turn end reaches
  // it through `#settle` — a lane's own tail, an adopted lane's re-armed tail,
  // a restored worker the host settles, and a lane that had already finished
  // when this table took it over (`#settleTail` drains that case). A live lane
  // is therefore never asked first: doing so answered the FIRST LANE in map
  // order rather than the first turn that came back, and it let a lane's own
  // `take` hand a report over while the queue still held it, which hid the
  // worker behind it.
  takeReady() {
    return this.claim();
  }

  // Wait for the roster to change, or for one macrotask to pass.
  //
  // The macrotask matters: a host may report a session as busy while its turn
  // has already ended in another process, and re-checking on the microtask
  // queue would spin without ever yielding to the code that settles it.
  async waitForChange() {
    const before = this.#generation;
    await Promise.race([this.#nextArrival(), new Promise((resolve) => setTimeout(resolve, 0))]);
    return this.#generation !== before;
  }

  // A worker this table does not hold (a restored entry, or a worker of a
  // session that ended) reports its outcome here. It becomes claimable at once,
  // and a waiting `join` is woken.
  settle(name, { error = null, text = null, role = null, agentId = null } = {}) {
    // A live lane of that name reports through its own tail (`#settle`), which
    // queues the turn's result in settle order; this entry point is for a
    // restored worker this table cannot drive.
    const known = this.#restored.entries.find((entry) => entry.name === name) ?? null;
    if (known === null) return null;
    this.#restored.entries = this.#restored.entries.filter((entry) => entry.name !== name);
    const result = workerResult({
      name,
      agentId: agentId ?? known.agentId,
      role: role ?? known.role,
      text,
      startedAt: known.startedAt,
      error,
      restored: true,
    });
    const index = this.#unclaimed.findIndex((entry) => entry.name === name);
    if (index === -1) this.#unclaimed.push(result);
    else this.#unclaimed[index] = result;
    this.#record({ type: WORKER_RECORD_TYPES.SETTLE, name, agentId: result.agentId, text: result.text, error });
    this.#onArrive?.(result);
    this.#wake();
    return result;
  }

  // Take over a worker that is still running in this process under a roster
  // this table did not build: a reload rebinds the extension, and the host's
  // events reach the lane the previous binding made.
  //
  // The lane is rebuilt over the SAME session, so its turn end is observed
  // again and the result is claimable here. Nothing about the turn is replayed
  // (`Lane.adopt`): the entry stops being a restored placeholder and becomes a
  // real lane, so `join` waits on the turn instead of on a `settle` nobody will
  // send.
  //
  // A name this table already holds a live lane for is left alone.
  adopt(name, { session, role = null, agentId = null, startedAt = null, observeFrom = null } = {}) {
    const known = this.#restored.entries.find((entry) => entry.name === name) ?? null;
    if (known === null) return null;
    if (session === null || session === undefined || typeof session.subscribe !== "function") return null;
    if (this.#lanes.get(name) !== undefined) return null;
    const lane = new Lane({
      name,
      role: role ?? known.role,
      session,
      agentId: agentId ?? known.agentId ?? name,
      clock: () => startedAt ?? known.startedAt ?? this.#clock(),
    });
    // The journal says this worker is RUNNING, so its turn must be able to end
    // here: a lane adopted as finished would take a `steer` as a new `start` and
    // prompt a session that is still busy.
    lane.adopt({ observeFrom });
    this.#restored.entries = this.#restored.entries.filter((entry) => entry.name !== name);
    this.#lanes.set(name, lane);
    // A second reload adopts this lane again: the journal's entry is already
    // gone from this table, so the successor learns the worker through the
    // successor registry (`adoptedTo`) and re-arms the same turn here.
    this.#settleTail(lane);
    // A worker the journal says FINISHED ended its turn before this roster
    // existed; its result came in with the journal's `unclaimed` list, so this
    // table does not settle it again.
    this.#wake();
    return lane;
  }

  // Steer a live worker: the brief joins its running turn, or starts its next
  // one when it is idle.
  async steer(name, brief) {
    const lane = this.#lanes.get(name);
    if (lane === undefined) return false;
    // A steer starts new work on the same worker. Results are per TURN and a
    // returned result is never withdrawn: a finished turn whose report nobody
    // joined is work the worker did and the Manager has not seen, so it stays
    // queued in settle order and `join` hands it over first (§4.1). Only a LIVE
    // turn (no result yet) can be steered, and that steer produces no extra
    // result. The new turn is recorded so a reload knows a turn is running.
    if (lane.settled) this.#record({ type: WORKER_RECORD_TYPES.STEER, name, agentId: lane.agentId });
    const steered = await lane.steer(brief);
    // A steer on a STREAMING session joins the turn already running, and that
    // turn's tail is already armed; a steer on an idle one starts a new turn
    // (`Lane.start` makes a new end promise). `#settleTail` is a no-op when the
    // turn it would watch is the one already watched, so asking here is safe
    // either way and covers the idle case.
    if (steered) this.#settleTail(lane);
    return steered;
  }

  // True while this table is inside `start`. A session being created has no
  // roster of its own yet, so an SDK that resolves to the creator's table at
  // that moment must refuse rather than fork into the start it is inside.
  starting = false;

  async start({
    name,
    // The lane's role is a required fact (§2.1, §4.1): it decides the face, the
    // gate and the identity a fission group inherits. An absent one is refused
    // by name below rather than defaulted to coder; the `null` default only
    // makes "absent" observable, and the literals keep this module import-free
    // (its own tests run it without `host-facts.mjs`).
    role = null,
    brief,
    systemPrompt = null,
    cwd = null,
    model = null,
    extensionPaths = this.#extensionPaths,
    parentAgentId = this.#parentAgentId,
    taskDepth = this.#taskDepth,
    workerToolsFor = this.#workerToolsFor,
    // The face follows the ROLE. `toolNamesFor` answers per lane; the explicit
    // `workerToolNames` argument still wins when a caller passes one.
    workerToolNames = this.#faceFor(role),
    // `register` says whether the started lane joins this roster. A `fork`
    // worker does: `join` collects it. A FISSION lane does not — the group owns
    // its lanes (`ports.mjs`), and putting them in the owner's roster would let
    // `join` collect a lane the group has not converged yet. The lane is still
    // fully built, wired, authorized and started; only the roster entry differs.
    register = true,
    // `parentSessionFile` names the transcript a successor conversation replaces
    // (`newSession({ parentSession })`, `session-manager.ts:2465`). A relay's
    // successor records the incumbent's file so the lineage is on disk.
    parentSessionFile = null,
    // `agentId` overrides the IRC id. A relay's successor takes the incumbent's
    // id so `write agent://<id>` keeps reaching the same participant; the lane
    // name is still its own (`<id>#relay<n>`), so the roster never collides.
    agentId = name,
    // `depth` overrides the depth the table was built with. A table belongs to
    // ONE session, and the host reports that session's depth on its own agent
    // object; a caller that knows the CALLING session's depth (a worker's own
    // adapter, whose ctx is that worker) passes it so the child is one level
    // deeper instead of all workers sharing the starter's number. The host's
    // `task.maxRecursionDepth` is not applied to a session created here (no
    // task tool is constructed), so this is a fact for logs and for the host's
    // own accounting, never a cap.
    depth = null,
  }) {
    if (typeof depth === "number" && Number.isFinite(depth) && depth >= 1) taskDepth = depth;
    // The RAW module, never a wrapper: a wrapper resolves back to this very
    // table and `start` would re-enter itself (see `rawFactoryOf`).
    const create = rawFactoryOf(this.#api);
    if (create === null) return { ok: false, reason: "NO_SESSION_FACTORY" };
    // Only the Manager or one of the two worker identities may own a lane; a
    // lane of unknown identity would be created with the host's own face.
    if (role !== "manager" && role !== "coder" && role !== "devops") {
      return { ok: false, reason: "UNDECLARED_ROLE" };
    }

    const existing = this.#lanes.get(name);
    if (existing !== undefined) {
      if (!existing.settled) return { ok: false, reason: "NAME_TAKEN" };
      // A finished lane's next brief continues in the same session (§4.1). The
      // new turn is recorded before it starts; the previous turn's result stays
      // queued (results are per turn and are never withdrawn), so `join` still
      // hands over the answer nobody collected.
      this.#record({ type: WORKER_RECORD_TYPES.STEER, name, agentId: existing.agentId });
      existing.start(brief);
      this.#settleTail(existing);
      return { ok: true, lane: existing, continued: true };
    }

    // The worker face is built for THIS worker. It cannot travel as
    // `customTools`: the host snapshots those while creating the session, so
    // they would carry whichever session's closures built them. The lane
    // installs this build on the worker's own session once the host creates it
    // (below).
    let workerBuild = null;
    if (typeof workerToolsFor === "function") {
      try {
        // The worker's API: the parent's `registerTool`, and the parent's SDK
        // wrapped so every session it creates lands in THIS roster. The lane's
        // ROLE travels with it: the face a lane is built with is the face its
        // role owns, so a Manager lane gets `js-manager`, never `js-worker`.
        // The worker's SDK must fork into the WORKER's roster, not this lane's:
        // the thunk is read when the worker's own session exists, so its
        // children belong to it (see `workerSdk`).
        // Read at call time. A worker created through this SDK is bound and
        // given its own roster before it can call anything, so this answers the
        // worker's table; the lane's own table is used only while the worker's
        // session is still being created (the build must exist before `install`).
        // Read at call time, never captured: a worker created through this SDK
        // is bound and given its own roster before it can call anything, so this
        // answers the WORKER's table and its children are its own. Before that
        // bind it answers `null`, and `workerSdk` refuses the start rather than
        // falling back to this lane's roster (which is what put a worker's child
        // in the Manager's journal).
        const lanesFor = () => workerBuild?.adapter?.session?.lanes ?? null;
        const apiArg = workerExtensionApi(this.#api, lanesFor);
        workerBuild = workerToolsFor(apiArg, this, role) ?? null;
      } catch (error) {
        return { ok: false, reason: "WORKER_TOOLS_FAILED", detail: String(error?.message ?? error) };
      }
    }

    let session;
    // While this table is inside `start`, an SDK that resolves to it is the
    // creator's roster, not the new session's — see `workerSdk`.
    this.starting = true;
    try {
      const created = await create({
        ...(cwd === null ? {} : { cwd }),
        ...(model === null ? {} : { model }),
        ...(systemPrompt === null ? {} : { systemPrompt }),
        taskDepth: Math.max(1, taskDepth),
        agentId,
        agentName: role,
        agentDisplayName: name,
        ...(parentAgentId === null ? {} : { parentAgentId }),
        ...(parentSessionFile === null ? {} : { parentSession: parentSessionFile }),
        ...(extensionPaths.length === 0 ? {} : { additionalExtensionPaths: [...extensionPaths] }),
        // The face is fixed at creation: `setActiveTools` throws inside a
        // session the host never initialized.
        ...(workerToolNames.length === 0 ? {} : { toolNames: [...workerToolNames] }),
        // No `customTools`: a session created through the SDK has no extension
        // runtime, so its own tools are installed by this lane right after
        // creation (`workerBuild.install`), never taken from another session.
      });
      session = created?.session;
    } catch (error) {
      this.starting = false;
      workerBuild?.dispose?.();
      return { ok: false, reason: "SESSION_CREATE_FAILED", detail: String(error?.message ?? error) };
    } finally {
      this.starting = false;
    }
    if (session === null || session === undefined || typeof session.prompt !== "function") {
      workerBuild?.dispose?.();
      return { ok: false, reason: "SESSION_CREATE_FAILED", detail: "createAgentSession returned no usable session" };
    }

    const lane = new Lane({
      name,
      role,
      session,
      agentId: typeof session.getAgentId === "function" ? (session.getAgentId() ?? name) : name,
      clock: this.#clock,
      // The worker's own adapter is released when the lane is disposed.
      release: workerBuild === null ? null : () => workerBuild.dispose?.(),
    });

    // The worker's own tools act on the worker's own session. The host emits no
    // event for a session it created through the SDK, so the builder's session
    // is handed over directly, before the handshake and before the first turn.
    // A builder whose session is not the one the host created would leave the
    // worker's programs acting on another session; the lane refuses it.
    if (workerBuild !== null && typeof workerBuild.install === "function") {
      try {
        // The lane's own role and face, so a session is never handed a face its
        // role does not own (the planner's, the Manager's, or the review face).
        // The role is passed explicitly: a session the host built may carry no
        // `agent`, and guessing would make a worker a Manager.
        await workerBuild.install(session, { only: [...workerToolNames], role });
      } catch (error) {
        await lane.dispose();
        return { ok: false, reason: "WORKER_TOOLS_FAILED", detail: String(error?.message ?? error) };
      }
      if (workerBuild.boundSession() !== session) {
        await lane.dispose();
        return {
          ok: false,
          reason: "WORKER_TOOLS_FAILED",
          detail: "the worker's tools were not bound to the worker's own session",
        };
      }
    }

    // Authorization: the worker's own adapter must bind and narrow. `hasHandlers`
    // is not evidence (another extension satisfies it); the token is.
    const runner = session.extensionRunner;
    if (runner === undefined || typeof runner.emit !== "function") {
      await lane.dispose();
      return { ok: false, reason: "EXTENSION_RUNTIME_UNAVAILABLE", detail: "the session exposes no extension runner" };
    }
    // The session's extension runtime is never initialized by the host for a
    // session created through the SDK (`runner.initialize` is called only from
    // the startup paths: `modes/runtime-init.ts:74`, `acp-agent.ts:2980`).
    // Without it every action throws "Extension runtime not initialized", so the
    // worker's own adapter cannot narrow and the worker would run with the
    // host's default face. Measured on the real host: with the real actions
    // wired the worker's face narrows; without them the handshake answers
    // `narrowed:false`.
    //
    // A session missing any member those actions forward to is refused here
    // rather than wired with a placeholder: a placeholder would let the worker
    // answer "done" for work that never reached the session.
    try {
      const wired = wireWorkerRuntime(runner, session);
      if (!wired.ok) {
        await lane.dispose();
        return { ok: false, reason: "RUNTIME_INIT_FAILED", detail: wired.reason };
      }
    } catch (error) {
      await lane.dispose();
      return { ok: false, reason: "RUNTIME_INIT_FAILED", detail: String(error?.message ?? error) };
    }

    const token = `herd:${name}:${this.#clock()}:${Math.random().toString(36).slice(2)}`;
    let handshake = null;
    try {
      await runner.emit({ type: "session_start", [HANDSHAKE]: { token, reply: (result) => { handshake = result; } } });
    } catch (error) {
      await lane.dispose();
      return { ok: false, reason: "SESSION_START_FAILED", detail: String(error?.message ?? error) };
    }
    const authorized = handshake !== null && handshake.token === token && handshake.narrowed === true;
    if (!authorized) {
      await lane.dispose();
      return {
        ok: false,
        reason: "WORKER_NOT_AUTHORIZED",
        detail:
          (handshake === null
            ? "no Herdchestra adapter answered inside the worker session"
            : `the worker's Herdchestra adapter answered but did not narrow (${handshake.reason ?? "unknown"})`) +
          "; refusing to start a worker without the Herdchestra face and gate",
      };
    }

    // A lane the roster does not own is still returned whole; only the entry
    // differs, so the caller that built it (a fission group) is the one that
    // holds and disposes it. It is never in the table, not even for a moment:
    // `join` and `steer` read this table, and a lane of an unconverged group is
    // not theirs to collect.
    // `brief === null` builds the session WITHOUT its first turn: the lane is
    // fully created, wired, authorized and narrowed, but it produces no turn and
    // is therefore not a live tenure. A relay needs exactly that shape — a
    // successor that exists and has an identity, before the record that names it
    // is committed.
    //
    // An inert lane is never registered: `join` and `steer` read this table, and
    // a lane that has not worked is not theirs to collect. `null` is the only
    // unambiguous marker; an empty brief is a legal (if pointless) brief.
    const inert = brief === null || brief === undefined;
    if (!inert && register !== false) {
      this.#lanes.set(name, lane);
      // The registration reaches the disk BEFORE the first turn: a reload
      // between the two steps must still know this worker exists, or it would
      // run with nobody holding its name.
      this.#record({ type: WORKER_RECORD_TYPES.FORK, name, agentId: lane.agentId, role, startedAt: lane.startedAt });
    }
    // Returning here also skips `await lane.submitted`, which is correct twice
    // over: `#submitted` is created INSIDE `Lane.start`, so an inert lane has
    // none, and awaiting it would hang forever on a promise nobody resolves.
    if (inert) return { ok: true, lane, inert: true };
    lane.start(brief);
    // The tail is armed AFTER the turn exists: `Lane.start` gives the turn its
    // own end promise, and a tail armed before it would watch the promise the
    // constructor made and then throw away — so the worker's end would never
    // reach the journal and a reload would find it still "running".
    if (register !== false) this.#settleTail(lane);
    // The first turn must be accepted before the lane counts as started; the
    // wait is on submission only, never on the turn.
    const submitted = await lane.submitted;
    if (submitted.ok !== true) {
      if (register !== false) {
        this.#lanes.delete(name);
        // The turn never started, so the name is free again: the journal drops
        // the registration instead of leaving a worker that never ran.
        this.#record({ type: WORKER_RECORD_TYPES.RELEASE, name, agentId: lane.agentId });
      }
      await lane.dispose();
      return { ok: false, reason: "TURN_START_FAILED", detail: submitted.error?.message ?? "turn rejected" };
    }
    return { ok: true, lane };
  }

  async disposeAll() {
    // Marked FIRST, and the waiters are woken whatever the teardown does: a
    // `join` already waiting on this roster is armed on a turn that this
    // teardown removes, so it must be released with the fact, not left pending
    // for a process lifetime. A lane that fails to dispose must not keep a
    // caller waiting either, which is why the wake is not inside the loop.
    this.#disposed = true;
    // Captured BEFORE the table is emptied, so the refusal a released `join`
    // receives can still name the workers it was waiting for.
    this.#disposedNames = this.names();
    this.#wake();
    for (const lane of this.all()) await lane.dispose();
    this.#lanes.clear();
    this.#wake();
  }
}
