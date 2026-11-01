// Host calls the adapter makes (spec §7.3).
//
// One place for every `ctx`/`pi` call, so the rest of the adapter is testable
// without a host and every assumption about OMP lives next to the fact that
// justifies it (see `host-facts.mjs`). Nothing here invents a result: a call the
// host does not offer returns `{ ok: false, reason }` with the reason named.
//
// Two capabilities matter:
//   - `runAgent(agent, name, brief)` spawn and wait for the one answer (`task`
//     with a blocking agent definition, task/executor.ts `itemBlocking`). This
//     is `Plan`'s `this.devops`, and it is the ONLY caller of the `task` tool in
//     this adapter;
//   - `steer(agentId, brief)` deliver a brief to a live worker's own session
//     (`write agent://<id>`, OMP's IRC bridge), used when a job already exists.
//
// `fork`/`join` do NOT go through here: a worker is a real session started
// through the SDK the host injects (`api.pi.createAgentSession`, `lane.mjs`),
// because an extension has no dispatcher to reach `task` with.
//
// `ctx.invokeTool` is NOT a general dispatcher: it delegates only to a native
// built-in of the *same name* as the calling tool (extensions/types.ts:877,
// runner.ts `invokeNativeTool`), and our tools are named `js-*`. The adapter
// therefore takes an explicit `dispatch(toolName, params)` — supplied by the
// host wiring or by a test — and never guesses that `invokeTool` can reach
// another tool.


import { hasSessionFactory } from "./lane.mjs";

export const CAPABILITY = Object.freeze({
  // The real host gives an extension no way to run another tool: the factory
  // receives only `api` (loader.ts:454), `api` has no dispatcher, and
  // `ctx.invokeTool` is same-tool-only and absent from the real
  // `session_start` context. Every host-tool call therefore answers this.
  NO_HOST_DISPATCH: "NO_HOST_DISPATCH",
  NO_AGENT_CHANNEL: "NO_AGENT_CHANNEL",
  NO_STEER_CHANNEL: "NO_STEER_CHANNEL",
  STEER_UNDELIVERED: "STEER_UNDELIVERED",
  AGENT_FAILED: "AGENT_FAILED",
});

export const textOf = (result) => {
  if (result === null || typeof result !== "object") return "";
  const parts = Array.isArray(result.content) ? result.content : [];
  return parts.map((part) => (part?.type === "text" ? part.text : "")).join("\n");
};

// Whether a `write agent://<id>` result reports delivery. The IRC write handler
// answers "Delivered to <id>." / "Queued for <id> (was parked; revived)." and
// sets `isError` when no receipt succeeded (agent-protocol.ts `write`,
// irc/messaging.ts `executeSend`).
export const steerDelivered = (result) => {
  if (result === null || typeof result !== "object") return false;
  if (result.isError === true) return false;
  return /^(Delivered to|Queued for) /m.test(textOf(result));
};

// The registry id of the first spawned agent in a `task` result. OMP reports it
// as `details.progress[0].id` (every spawn) and `details.results[0].id`
// (blocking spawns); both are the same string.
export const agentIdOf = (result) => {
  const details = result?.details;
  if (details === null || typeof details !== "object") return null;
  const fromProgress = Array.isArray(details.progress) ? details.progress[0]?.id : undefined;
  if (typeof fromProgress === "string" && fromProgress.length > 0) return fromProgress;
  const fromResults = Array.isArray(details.results) ? details.results[0]?.id : undefined;
  if (typeof fromResults === "string" && fromResults.length > 0) return fromResults;
  return null;
};

// The job id of a detached spawn: `details.async.jobId` (task/index.ts:1306,
// `primaryJobId`), which is the first spawn's agent id. The registry id is the
// fallback, because the `agent://` handle is the registry id.
export const jobIdOf = (result) => {
  const async = result?.details?.async;
  if (async !== null && typeof async === "object") {
    const jobId = async.jobId;
    if (typeof jobId === "string" && jobId.length > 0) return jobId;
  }
  return agentIdOf(result);
};

// One host binding for one session. `dispatch` is
// `(toolName, params) => Promise<result>`; tests pass a fake and record calls.
export class HostBinding {
  #pi;
  #api;
  #ctx;
  #dispatch;

  constructor({ pi = null, api = null, ctx = null, dispatch = null }) {
    this.#pi = pi;
    // The host's SDK namespace (`api.pi`), which is where a session factory
    // lives. Kept beside the extension API so `fork` never guesses.
    this.#api = api ?? pi ?? null;
    this.#ctx = ctx;
    this.#dispatch = dispatch;
  }

  // Adopt the context of the event now being handled. The host builds a new
  // `ExtensionContext` per emit, so `ui` (the `ask` dialog) and every other
  // live member must be read from the current one, not from `session_start`'s.
  refresh(ctx) {
    if (ctx === null || ctx === undefined || typeof ctx !== "object") return this;
    this.#ctx = ctx;
    return this;
  }

  get api() {
    return this.#api;
  }

  get ctx() {
    return this.#ctx;
  }

  get pi() {
    return this.#pi;
  }

  get ui() {
    return this.#ctx?.ui ?? null;
  }

  // Whether this host can run a tool on the extension's behalf. The real host
  // cannot (see `CAPABILITY.NO_HOST_DISPATCH`); a test harness that injects a
  // dispatcher can.
  canDispatch() {
    return typeof this.#dispatch === "function";
  }

  // Whether this host can start a worker, which is what `fork` needs: the
  // `task` tool, reached through the injected dispatcher. A host that cannot
  // dispatch cannot fork, and says so instead of pretending.
  // A worker is started through the SDK the host injects (`api.pi.
  // createAgentSession`), not through a tool dispatcher: the real host supplies
  // no dispatcher at all.
  canStartWorker() {
    return hasSessionFactory(this.#api);
  }

  // Run a host tool by name.
  async invoke(name, params) {
    if (typeof this.#dispatch !== "function") {
      return {
        ok: false,
        reason: CAPABILITY.NO_HOST_DISPATCH,
        tool: name,
        detail:
          "OMP passes only `api` to an extension factory and offers no general tool dispatcher; " +
          "the `task` tool and every other host tool cannot run from here",
      };
    }
    return await this.#dispatch(name, params);
  }

  // Start an independent session and return at once. `async: true` asks the
  // `task` tool for a background job; without it a blocking agent definition
  // would make the call wait for the worker's whole turn (spec §4.1: `fork` is
  // fire-and-forget).
  async spawn({ agent, name, brief }) {
    const result = await this.invoke("task", { agent, name, task: brief, async: true });
    if (result === null || typeof result !== "object" || result.ok === false) {
      return { ok: false, reason: result?.reason ?? CAPABILITY.NO_AGENT_CHANNEL, tool: "task" };
    }
    if (result.isError === true) {
      return { ok: false, reason: CAPABILITY.AGENT_FAILED, text: textOf(result), details: result.details ?? null };
    }
    const agentId = agentIdOf(result);
    if (agentId === null) {
      return { ok: false, reason: CAPABILITY.AGENT_FAILED, text: textOf(result), details: result.details ?? null };
    }
    return { ok: true, agentId, jobId: jobIdOf(result) ?? agentId, details: result.details ?? null };
  }

  // Spawn an independent session through the host's `task` tool and wait for
  // its one answer. The agent must be a blocking definition (`blocking: true`
  // in its frontmatter; task/executor.ts `itemBlocking`), otherwise `task`
  // returns before the work starts and there is no answer to wait for.
  async runAgent({ agent, name, brief }) {
    const result = await this.invoke("task", { agent, name, task: brief });
    if (result === null || typeof result !== "object" || result.ok === false) {
      return { ok: false, reason: result?.reason ?? CAPABILITY.NO_AGENT_CHANNEL, tool: "task" };
    }
    const text = textOf(result);
    if (result.isError === true || text.length === 0) {
      return { ok: false, reason: CAPABILITY.AGENT_FAILED, text, details: result.details ?? null };
    }
    return { ok: true, text, details: result.details ?? null, agentId: agentIdOf(result) };
  }

  // Deliver a brief to a live worker's own session. OMP's IRC bridge is the
  // only cross-session channel an extension has (`pi.sendMessage` reaches the
  // calling session only); the write reports "Delivered to" or "Queued for"
  // when the recipient took it, and that text is the only delivery proof.
  async steer(agentId, brief) {
    if (typeof agentId !== "string" || agentId.length === 0) {
      return { ok: false, reason: CAPABILITY.NO_STEER_CHANNEL };
    }
    const result = await this.invoke("write", { path: `agent://${agentId}`, content: brief });
    if (result !== null && typeof result === "object" && result.ok === false) return result;
    return steerDelivered(result) ? { ok: true } : { ok: false, reason: CAPABILITY.STEER_UNDELIVERED };
  }

  // Ask a live worker for its report so far (`write agent://<id>?progress`).
  // The progress form answers with the agent's status and its progress text
  // instead of delivering a message (internal-urls/agent-protocol.ts:10-11).
  // An answer that is not the progress document is reported as unavailable.
  async progressOf(agentId) {
    if (typeof agentId !== "string" || agentId.length === 0) {
      return { ok: false, reason: CAPABILITY.NO_STEER_CHANNEL };
    }
    const result = await this.invoke("write", { path: `agent://${agentId}?progress`, content: "status" });
    if (result === null || typeof result !== "object" || result.isError === true) {
      return { ok: false, reason: CAPABILITY.NO_STEER_CHANNEL, text: textOf(result) };
    }
    return { ok: true, text: textOf(result) };
  }

  // Stop one live session. `ctx.abort()` aborts the session whose context this
  // is (extensions/types.ts `ExtensionContext.abort`), so it can only stop the
  // caller's own session, never another one. An extension is never handed
  // another session's context, so this reports the missing channel instead of
  // claiming a stop it did not perform.
  async stopLane(agentId) {
    if (typeof agentId !== "string" || agentId.length === 0) {
      return { ok: false, reason: "NO_STOP_CHANNEL" };
    }
    return {
      ok: false,
      reason: "NO_STOP_CHANNEL",
      detail: `OMP exposes abort() only on a session's own context; agent ${agentId} is another session`,
    };
  }
}
