// One delegated runtime for a worker or a lane (spec §4.1, §4.2, §7.2).
//
// `createDelegatedAgent({ kind: "subagent", prompt, tools, configProvider,
// extensions })` is the host factory. Its `prompt` argument is the subagent's
// system prompt — the host wraps it (`buildSubAgentSystemPrompt`) and does not
// add its own role text on top — so the worker's prompt is exactly the
// `COMMON + WORKER` composition, and the first user turn is the brief.
//
// ## Three things this handle has to get right, each measured in `../cline`
//
// 1. THE WORKER FACE SURVIVES A FRONTMATTER LIST. A configured agent's
//    `createSubAgentTools` (`extensions/tools/team/configured-agent-tool.ts:160`,
//    wired from `runtime/orchestration/runtime-builder.ts:662`) answers the tool
//    list of a subagent a person defined in frontmatter. A runtime started
//    through that path would get THAT list and lose `js-worker`. This handle
//    decorates the runtime's own `tools` map before the first turn: the face it
//    was built with is put back, whatever the host put there.
//
// 2. A STEER REACHES THE RUNNING TURN. `SessionRuntime.run()` rejects while a
//    turn is in flight (`session-runtime-orchestrator.ts:773`), and the plugin
//    cannot reach the hub's `send_input` queue for a delegated runtime (the
//    session is not in `LocalRuntimeHost.sessions`). What IS reachable is the
//    runtime's own pending-input channel: the agent loop reads
//    `consumePendingUserMessage()` at EVERY request boundary
//    (`packages/agents/src/agent-runtime.ts:1689`), and `SessionRuntime`
//    forwards that member from its config (`runtime/config/agent-runtime-config-builder.ts:117`).
//    A runtime created without one reads nothing; this handle installs one, so
//    a steer is taken at the next request of the turn already running.
//
// 3. REPLACING A PRESENT FROM ITS OWN TURN. `abort()` ends the run that is
//    executing the calling tool; awaiting it from inside that run waits for the
//    call itself. `atTurnEnd(fn)` defers it to the boundary the host owns
//    (`afterRun`, drained by the adapter), which is what step 6 needs.
//
// The factory is injected. Nothing here imports `@cline/core`.

// The callbacks armed for the end of a runtime's current turn, keyed by the
// runtime OBJECT rather than by this handle: the adapter rebuilds a handle when
// the host re-binds a session, and an abort armed by the previous handle must
// still run at the boundary the new one sees.
const turnEndHooks = new WeakMap();

// Put the worker face back into a runtime whose tool list the host rebuilt.
//
// `SessionRuntime` keeps its tools in a private `config.tools` array and hands
// them to every turn (`session-runtime-orchestrator.ts:975`). The host's own
// append path is `addTools` (`session-runtime-orchestrator.ts:552`): it merges
// by name into `config.tools` and dedupes, so the LAST registration of a name
// wins on the next turn. That is the surface this adapter uses — no private
// field is read or written, and a runtime without it keeps whatever it has.
//
// `addTools` can only ADD. A name the host narrowed away (a frontmatter `tools:`
// list that never mentioned `js-worker`) is put back; a host tool the list kept
// stays, and `hooks.beforeTool` is what refuses it for a worker.
export const enforceFace = (agent, tools) => {
  if (agent === null || typeof agent !== "object") return { ok: false, reason: "NO_RUNTIME" };
  const wanted = (Array.isArray(tools) ? tools : []).filter(
    (tool) => tool !== null && typeof tool === "object" && typeof tool.name === "string",
  );
  if (wanted.length === 0) return { ok: false, reason: "NO_FACE" };
  if (typeof agent.addTools !== "function") return { ok: false, reason: "NO_TOOL_SURFACE" };
  try {
    agent.addTools(wanted);
  } catch (error) {
    return { ok: false, reason: "FACE_REFUSED", message: String(error?.message ?? error) };
  }
  return { ok: true, names: wanted.map((tool) => tool.name) };
};

// The members a runtime is handed: the host's own, plus the one input channel
// this adapter owns.
//
// The adapter's queue is ALWAYS drained first, and the host's own consumer is
// chained behind it. Returning the host's config untouched when it already had a
// `consumePendingUserMessage` left this adapter's queue with no reader at all:
// `steer()` still pushed the brief and answered `true`, so the table counted a
// carried turn while the text was silently lost. The host's member is not
// dropped — a runtime the host wired with its own queue still reads it.
const decorateConfigProvider = (configProvider, queue, counter) => {
  if (configProvider === null || typeof configProvider !== "object") return configProvider;
  if (typeof configProvider.getRuntimeConfig !== "function") return configProvider;
  return {
    ...configProvider,
    getRuntimeConfig: () => {
      const config = configProvider.getRuntimeConfig();
      if (config === null || typeof config !== "object") return config;
      // The agent loop awaits this member at every request boundary and trims
      // what it gets (`agent-runtime.ts:2302`): the answer is the steer's TEXT,
      // and the runtime builds the user message itself. An empty answer is "no
      // pending message", so an empty queue answers nothing.
      const hostConsume =
        typeof config.consumePendingUserMessage === "function" ? config.consumePendingUserMessage.bind(config) : null;
      return {
        ...config,
        consumePendingUserMessage: async () => {
          const next = queue.shift();
          if (next !== undefined) {
            // How many steered briefs this turn read at its boundary: the report
            // that ends the turn answers each of them.
            counter.read += 1;
            return next;
          }
          return hostConsume === null ? "" : await hostConsume();
        },
      };
    },
  };
};

// The text of one steer: a string, or an object carrying `prompt`. The host
// turns this text into its own message at the boundary it reads it, so the
// queue holds text and nothing here invents a message shape.
const steerText = (value) => {
  const text = typeof value === "string" ? value : typeof value?.prompt === "string" ? value.prompt : null;
  if (text === null || text.trim().length === 0) return null;
  return text;
};

export const runtimeHandle = ({
  name,
  brief,
  role,
  create,
  configProvider,
  tools,
  extensions,
  cwd,
  prompt,
  modelSource = null,
  credentialSource = null,
  // `false` builds the runtime WITHOUT its first turn (spec §4.2 step 4). The
  // handle then reports `pending: true`, and `run(brief)` sends that turn.
  start = true,
}) => {
  if (create === null || typeof create !== "function") {
    return {
      started: false,
      rejection: "HostCapabilityMissing",
      message: "the host did not provide @cline/core createDelegatedAgent to the plugin",
    };
  }
  // One entry per steer: the host consumes one message per request boundary.
  const queue = [];
  // Steered briefs the turn in flight read at its request boundaries. Reset
  // when a turn settles.
  const readSteers = { read: 0 };
  let agent;
  try {
    agent = create({
      kind: "subagent",
      prompt,
      tools,
      configProvider: decorateConfigProvider(configProvider, queue, readSteers),
      extensions,
      cwd,
      role,
      // The plugin's own hooks travel with the child: a worker that lost them
      // would have a `js-worker` tool with no program behind it.
      hooks: extensions?.hooks,
    });
  } catch (error) {
    return {
      started: false,
      rejection: "HostCapabilityMissing",
      message: String(error?.message ?? error),
    };
  }
  if (agent === null || typeof agent?.run !== "function") {
    return { started: false, rejection: "HostCapabilityMissing", message: "the delegated agent has no run()" };
  }
  // Gap 2: the face this runtime was built with is put back after the host
  // built its tool map. A runtime that does not expose one is left as it is,
  // and `face` says so instead of claiming a defence that did not run.
  const face = enforceFace(agent, tools);

  const listeners = [];
  let inFlight = 0;
  // A settled turn nobody had a listener for yet. The first turn can end before
  // the table registers its callback, and that result must still reach it.
  const finished = [];
  // One settled turn. The first turn is `start`; each later one comes from
  // `run` (a steer taken when the worker was idle) and is reported the same way.
  const settle = (result) => {
    const record = {
      finishReason: result?.finishReason ?? "unknown",
      text: typeof result?.text === "string" ? result.text : "",
      iterations: typeof result?.iterations === "number" ? result.iterations : 0,
      // How many steered briefs the turn took into itself. The table settles
      // that many turns from this one report.
      steered: readSteers.read,
    };
    readSteers.read = 0;
    if (listeners.length === 0) finished.push(record);
    else for (const listener of listeners) listener(record);
    return record;
  };
  // The boundary of one turn. Every callback armed for it runs HERE, before the
  // turn is reported: the abort that replaces a present (§4.2 step 6) must have
  // happened by the time anything observes the turn as over, and it must not be
  // run while the tool call that armed it is still on the stack.
  const drain = () => {
    const armed = turnEndHooks.get(agent) ?? [];
    turnEndHooks.delete(agent);
    for (const fn of armed) {
      try {
        fn();
      } catch {
        // One callback's failure is its own; the rest still run.
      }
    }
    return armed.length;
  };
  const turn = (value) => {
    inFlight += 1;
    return Promise.resolve()
      .then(() => agent.run(value))
      .catch((error) => ({ finishReason: "error", text: String(error?.message ?? error), iterations: 0 }))
      .then((result) => {
        inFlight -= 1;
        // A turn that ended is the boundary even when nobody asked the adapter
        // to drain it: a deferred abort armed by a turn that is now over has
        // nothing left to wait for.
        drain();
        return settle(result);
      });
  };
  const started = start ? turn(brief) : null;

  // The first turn is already in flight when `start`; `run` is the next turn,
  // which the table calls only after the previous one settled.
  return {
    started: true,
    name,
    role,

    // Which reading supplied the connection this runtime runs on (`request`,
    // `context`, `sessionRecord`, `environment`). A receipt that names it can
    // be checked against the session that produced it.
    modelSource,
    // Where the credentials came from (`providerSettings`, or `hostHasNone`).
    // A runtime reported as started without saying this is a claim nobody can
    // check against the provider's own error.
    credentialSource,
    // What the face defence did, for a receipt that has to explain itself.
    face,
    // True when no turn has been sent yet (a lane built for step 4).
    pending: !start,
    agent,
    // A steer into the turn that is running. `true` means the message is in the
    // runtime's queue and its next request boundary takes it; `false` means
    // there is no turn in flight to take it, and the caller starts one instead.
    steer: (value) => {
      const text = steerText(value);
      if (text === null) return false;
      if (inFlight === 0) return false;
      queue.push(text);
      // The host reads the queue at a request boundary; a model request that is
      // already streaming has passed its last one, so the runtime is told a
      // message is waiting. `notifyPendingUserMessage` interrupts ONLY the model
      // request (`packages/agents/src/agent-runtime.ts:633`): running tools
      // finish, and the next request reads the queue.
      try {
        agent.notifyPendingUserMessage?.();
      } catch {
        // The queue is the delivery; the notification is only its prompt.
      }
      return true;
    },
    // The next turn of the same runtime. A runtime built inert sends its FIRST
    // turn here (the lane's `beginLane`), and a worker idle between turns sends
    // the brief a steer left for it.
    run: (nextBrief) => turn(nextBrief),
    abort: (reason) => agent.abort?.(reason),
    // The runtime's own hook bag, for the hooks that are about THIS runtime's
    // turns. `SessionRuntime` merges the extension hooks into the bag it builds
    // (`createRuntimeHooks`), so the session's `beforeModel` already runs on the
    // child's requests; this is the other channel — a child-only hook, needed
    // when the parent's answer would be wrong for the child (a park in one
    // runtime must stop THAT run, never the parent's).
    //
    // The hooks are the parent extension's own handlers, so the host's
    // `afterTool` wrapper (`extensions/plugin/plugin-registry.ts` `wrapTool`)
    // still sees a plugin call. A runtime that exposes no hook bag answers
    // `false`, and the caller reports that instead of assuming it worked.
    addHooks: (extra) => {
      if (extra === null || typeof extra !== "object") return false;
      const config = agent?.config;
      if (config === null || typeof config !== "object") return false;
      const current = config.hooks === null || typeof config.hooks !== "object" ? {} : config.hooks;
      const merged = { ...current };
      for (const [stage, handler] of Object.entries(extra)) {
        if (typeof handler !== "function") continue;
        const existing = merged[stage];
        if (typeof existing === "function") merged[stage] = async (context) => {
          await existing(context);
          return handler(context);
        };
        else merged[stage] = handler;
      }
      try {
        config.hooks = merged;
      } catch {
        return false;
      }
      return true;
    },
    // Run `fn` at the end of the turn in flight, or at once when none is. The
    // adapter drains this from `afterRun`, the host's own boundary. Answering
    // `false` means there is no boundary this handle can reach, and a caller
    // that must stop the present has to refuse instead of pretending.
    atTurnEnd: (fn) => {
      if (typeof fn !== "function") return false;
      if (inFlight === 0) {
        fn();
        return true;
      }
      const armed = turnEndHooks.get(agent) ?? [];
      armed.push(fn);
      turnEndHooks.set(agent, armed);
      return true;
    },
    // The boundary, for a caller that wants to reach it explicitly (the
    // adapter's `afterRun`). Every armed callback runs once, in the order it was
    // armed; a callback that throws does not stop the others. The turn's own end
    // drains the same list, so a callback never runs twice.
    drainTurnEnd: () => drain(),
    onSettled: (callback) => {
      listeners.push(callback);
      // A turn that settled before the listener arrived is delivered to it:
      // the first turn can finish before the table registers its callback.
      for (const record of finished.splice(0)) callback(record);
      void started;
    },
  };
};
