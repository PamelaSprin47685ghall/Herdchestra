// Plan's dedicated DevOps worker (根 AGENTS.md §1.1 item 3, §2.2).
//
// Plan has one way to learn repository facts: `devops(verboseIntent)`, a BARE
// top-level primitive whose nature is join semantics — the program waits for the
// complete investigation record and continues with it. The primitive is bound
// beside `fork`/`join`/`review` (`execution.mjs` PRIMITIVES); there is no
// `this.devops`, and compose keeps the name out of Plan's member table
// (`SURFACE_PRIMITIVES`).
//
// The worker behind it is DEDICATED and SINGULAR:
//
//   - one runtime handle per session, fixed name `plan-devops`, built lazily on
//     the first investigation and kept for the session's whole life;
//   - each investigation is `handle.run(verboseIntent)`. A second runtime is
//     never created for a later call — the runtime's own conversation is the
//     DevOps worker's memory, and starting a fresh one per call threw that away
//     and paid the host's start-up cost every time;
//   - no listener accumulates: the turn's own promise is the report, so
//     `onSettled` is not used at all;
//   - a handle the operator's interrupt aborted is DEAD. The next investigation
//     refuses by name (`PLAN_DEVOPS_DEAD`) instead of quietly starting a second
//     DevOps behind the same plan.
//
// At most one investigation is unresolved per task. Compose refuses a second
// concurrent call with `DEVOPS_IN_FLIGHT`; this runner repeats the refusal as a
// value (`DEVOPS_ALREADY_IN_FLIGHT`) so a host that reaches it directly still
// gets the same rule.
//
// The connection is the TASK ladder (root §1.1 item 6), injected by the session
// (`resolveTaskConnection`): a subagent runs on the operator's task model when
// one is pinned, and on the main ladder otherwise. The receipt names which
// ladder answered.
//
// The factory is resolved at run time and injected, so this module never imports
// the host.

import { ROLES } from "../compose/program/surface.mjs";
import { systemPromptFor } from "../compose/prompt/projection.mjs";
import { guardedDelegatedApi } from "./host-modules.mjs";
import { runtimeHandle } from "./runtime.mjs";

// The one runtime name a session's Plan DevOps answers to. Fixed, not derived
// from the clock: the worker is a singleton of the session, and a receipt that
// names it is stable across investigations.
export const PLAN_DEVOPS_NAME = "plan-devops";

export const createDevopsRunner = ({
  ctx,
  cwd,
  extension,
  tools = [],
  resolveConnection = null,
  // The factory pair `fork` uses. Injected so a test can assert the connection
  // the runner asks for without starting a runtime on this machine's provider.
  delegatedApi = guardedDelegatedApi,
}) => {
  // The tool list is read at the call, not at construction: the tools this
  // adapter registers may not exist yet when the runner is built.
  const workerTools = () => (Array.isArray(tools) ? tools : []);
  let inFlight = null;
  // The session's one handle. It is built on the first investigation and kept
  // even when that investigation fails: a failed TURN is not a dead runtime, and
  // the next call reuses the same conversation.
  let handle = null;
  // Why the one handle could not be built, if it could not. Kept only for the
  // diagnostic: a later call retries the build (no runtime exists to duplicate).
  let buildFailure = null;

  // Build the handle, or answer the named refusal that prevented it.
  const build = async () => {
    // The guarded factory (root §7.2): the devops face survives a narrowed
    // tool list exactly as a forked worker's does.
    const delegated = await delegatedApi(() => workerTools());
    if (delegated === null) return { ok: false, reason: "HOST_HAS_NO_DELEGATED_AGENT" };
    // The task ladder: the operator's `HERDCHESTRA_TASK_*` pair when set, the
    // main ladder otherwise. A devops run is a subagent of the same task.
    const resolved =
      typeof resolveConnection === "function"
        ? await resolveConnection()
        : {
            model:
              ctx?.providerId !== undefined && ctx?.modelId !== undefined
                ? { providerId: ctx.providerId, modelId: ctx.modelId }
                : null,
            source: "context",
            credentials: null,
            credentialSource: null,
          };
    const connection = resolved?.model ?? null;
    if (connection === null || typeof connection.modelId !== "string" || connection.modelId.length === 0) {
      return {
        ok: false,
        reason: "MODEL_UNKNOWN",
        message: "no source named the task provider and model, so the devops runtime cannot connect",
        detail: { tried: ["taskEnvironment", "request", "context", "sessionRecord", "environment"] },
      };
    }
    const extensions = extension === null ? [] : [extension];
    const configProvider = delegated.createDelegatedAgentConfigProvider({
      providerId: connection.providerId,
      modelId: connection.modelId,
      cwd,
      extensions,
      // The host's stored key and base URL for this provider. Without them the
      // runtime starts and dies at the provider on "no API key".
      ...(resolved.credentials === null || resolved.credentials === undefined
        ? {}
        : { ...resolved.credentials, modelId: connection.modelId }),
    });
    const built = runtimeHandle({
      name: PLAN_DEVOPS_NAME,
      // Built INERT: the first investigation is the turn `investigate` sends
      // through `run`, so building the handle does not spend a model call.
      verboseIntent: "",
      start: false,
      role: ROLES.DEVOPS,
      create: delegated.createDelegatedAgent,
      configProvider,
      tools: workerTools(),
      extensions,
      cwd,
      prompt: systemPromptFor(ROLES.DEVOPS),
      modelSource: resolved.source ?? null,
      credentialSource: resolved.credentialSource ?? null,
    });
    if (built?.started === false) {
      return { ok: false, reason: built.rejection ?? "HOST_HAS_NO_DELEGATED_AGENT", message: built.message };
    }
    handle = built;
    buildFailure = null;
    return { ok: true, handle: built };
  };

  // One investigation on the one handle. The answer is the runtime's own record
  // text, verbatim — the spec forbids a second summary.
  const runInvestigation = async (verboseIntent) => {
    if (handle !== null && handle.dead === true) {
      return {
        ok: false,
        reason: "PLAN_DEVOPS_DEAD",
        message: "the plan-devops runtime was stopped by an operator interrupt; a new plan task starts a new devops",
      };
    }
    let prepared;
    if (handle === null) {
      prepared = await build();
      if (prepared.ok !== true) {
        buildFailure = prepared;
        return prepared;
      }
    } else {
      prepared = { ok: true, handle };
    }
    const runtime = prepared.handle;
    // The handle may have died while the connection was being resolved.
    if (runtime.dead === true) {
      return {
        ok: false,
        reason: "PLAN_DEVOPS_DEAD",
        message: "the plan-devops runtime was stopped by an operator interrupt; a new plan task starts a new devops",
      };
    }
    let record;
    try {
      record = await runtime.run(verboseIntent);
    } catch (error) {
      return { ok: false, reason: "DEVOPS_FAILED", message: String(error?.message ?? error) };
    }
    if (record?.finishReason === "error") {
      return { ok: false, reason: "DEVOPS_FAILED", message: record.text };
    }
    return { ok: true, report: record?.text ?? "" };
  };

  return {
    get busy() {
      return inFlight !== null;
    },
    // The handle this session holds, for a receipt or a test. Never a second one.
    get handle() {
      return handle;
    },
    get name() {
      return PLAN_DEVOPS_NAME;
    },
    // Why the last build failed, or null. Diagnostic only: no decision reads it.
    get buildFailure() {
      return buildFailure;
    },
    // Root §1.1 item 2: an operator interrupt at Plan reaches its DevOps too.
    // The handle is aborted (not disposed), so the runtime produces no further
    // turn; the `dead` flag then makes every later investigation refuse
    // `PLAN_DEVOPS_DEAD` instead of starting a second devops. A runner that
    // never built a handle has nothing to stop, and says so.
    cancel(reason = "OPERATOR_CANCELLED") {
      if (handle === null) return { ok: true, cancelled: false, reason: "NO_RUNTIME" };
      if (handle.dead === true) return { ok: true, cancelled: false, reason: "ALREADY_DEAD" };
      try {
        handle.abort(reason);
        return { ok: true, cancelled: true, name: PLAN_DEVOPS_NAME };
      } catch (error) {
        // `abort` marks the handle dead before it reaches the runtime, so the
        // refusal still holds; the receipt names the runtime that may keep
        // running.
        return { ok: false, cancelled: true, name: PLAN_DEVOPS_NAME, reason: "DEVOPS_ABORT_FAILED", message: String(error?.message ?? error) };
      }
    },
    // `investigate(verboseIntent)` answers `{ ok: true, report }` or
    // `{ ok: false, reason, message? }`.
    //
    // The admission is a single-flight promise set SYNCHRONOUSLY, before the
    // first `await`. Assigning it after the SDK and the connection resolved let
    // two concurrent calls both read `null` and both run a turn, which is
    // exactly the "at most one unresolved devops" rule §2.2 forbids.
    async investigate(verboseIntent) {
      if (inFlight !== null) return { ok: false, reason: "DEVOPS_ALREADY_IN_FLIGHT" };
      if (handle !== null && handle.dead === true) {
        return {
          ok: false,
          reason: "PLAN_DEVOPS_DEAD",
          message: "the plan-devops runtime was stopped by an operator interrupt; a new plan task starts a new devops",
        };
      }
      const admission = runInvestigation(verboseIntent);
      inFlight = admission;
      try {
        return await admission;
      } finally {
        if (inFlight === admission) inFlight = null;
      }
    },
  };
};
