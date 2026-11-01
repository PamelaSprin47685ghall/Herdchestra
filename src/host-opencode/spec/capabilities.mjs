// Tool names and the host facts they depend on (§7.1, §6). Pure data.
//
// Every fact below is read from `../opencode` (v2.1.4) at the path named in
// `source`. A fact the host source does not state is not here: it lives in
// `gaps.mjs` as an open question. Upgrading the host means re-checking this
// file, nothing else.

export const HOST_VERSION = "2.1.4";

/** Plugin loading: `@opencode/plugin` Promise API, `Plugin.define({ id, setup })`. */
export const PLUGIN_API = Object.freeze({
  package: "@opencode/plugin",
  define: "Plugin.define",
  moduleShape: "default = { id: string, setup: (ctx) => Cleanup | void }",
  id: "herdchestra",
  source: "packages/core/src/plugin/module.ts (Module schema, PluginPromise.fromPromise)",
});

/** Where `opencode.json` names the plugin. */
export const CONFIG_ENTRY = Object.freeze({
  field: "plugins",
  forms: ["package-string", "{ package, options }"],
  localPath:
    "a configured local target must be a directory; the entry resolves index.js or server.js inside it",
  directoryDiscovery: ".opencode/plugins/ is not scanned by v2.1.4; see gaps.mjs LOCAL_PLUGIN_ENTRY",
  source: "packages/schema/src/config/plugin.ts, packages/core/src/config/plugin/source.ts, packages/plugin/src/host.ts",
});

/** Agents the adapter creates (§2.1, §6). */
export const AGENTS = Object.freeze({
  manager: { id: "Manager", mode: "primary" },
  plan: { id: "Plan", mode: "primary" },
  coder: { id: "coder", mode: "subagent" },
  devops: { id: "devops", mode: "subagent" },
  transform: "ctx.agent.transform((editor) => editor.update(id, ...))",
});

/** Tools the adapter registers through `ctx.tool.transform` (§7.1). */
export const TOOL_NAMES = Object.freeze({
  manager: "js-manager",
  worker: "js-worker",
  review: "js-review",
  plan: "plan",
  fork: "fork",
  join: "join",
});

/**
 * Builtin tools the Manager must not see (§7.1). Visibility is narrowed per
 * request in `session.hook("context")`; every refusal is re-checked in our own
 * hooks (two layers, §7.1).
 */
export const MANAGER_FORBIDDEN_TOOLS = Object.freeze([
  "read",
  "grep",
  "glob",
  "write",
  "edit",
  "patch",
  "bash",
  "shell",
  "subagent",
]);

/**
 * Builtin tools Plan must not see (§6): no glob, no grep, no exec, and no file
 * reach beyond the draft its program writes.
 */
export const PLAN_FORBIDDEN_TOOLS = Object.freeze([
  "read",
  "grep",
  "glob",
  "write",
  "edit",
  "patch",
  "bash",
  "shell",
  "subagent",
  "task",
]);

/** The only host tool a worker keeps: the user question channel. */
export const WORKER_HOST_TOOLS = Object.freeze(["question"]);

/** Hook names, with the host file that triggers them. */
export const HOOKS = Object.freeze({
  context: 'session.hook("context") — packages/core/src/session/model-request.ts prepare()',
  prompt: 'session.hook("prompt") — packages/core/src/session/prompt.ts prepare()',
  executeBefore: 'tool.hook("execute.before") — packages/core/src/tool.ts beforeExecute()',
  executeAfter: 'tool.hook("execute.after") — packages/core/src/tool.ts executeTool()',
});

/**
 * Delivery of `Session.prompt`: the host default is `"steer"`, so a
 * fire-and-forget `fork` must pass `delivery: "queue"` explicitly (§7.1).
 */
export const DELIVERY = Object.freeze({
  default: "steer",
  fork: "queue",
  source: "packages/core/src/session/session.ts prompt()",
});

/** Request deadline per intent byte (spec §3.3). */
export const MS_PER_INTENT_BYTE = 50;

/** Subagent nesting limit: the builtin `subagent` tool only; see gaps.mjs. */
export const SUBAGENT_DEPTH = Object.freeze({ default: 1, config: "experimental.subagent_depth" });

/** Tool options the adapter sets: a program is already code (§7.1). */
export const TOOL_OPTIONS = Object.freeze({ codemode: false });

/**
 * What the session domain offers a tool executor (§4.1, §4.2, §2.3).
 *
 * `SessionDomain` in `packages/plugin/src/promise/session.ts` is a `Pick` of the
 * client's session API. The methods this adapter uses, and the files that
 * define them:
 *
 *   create   { parentID?, title?, agent?, metadata? } -> Session.Info
 *            packages/client/src/effect/api/api.ts SessionCreateInput
 *   prompt   { sessionID, text, delivery?, resume? } -> inbox user
 *            packages/core/src/session/session.ts prompt()
 *   synthetic{ sessionID, text, description?, metadata?, resume? }
 *            packages/core/src/session/session.ts synthetic()
 *   wait     { sessionID } -> void, resolves when the session is idle
 *            packages/core/src/session/execution.ts awaitIdle
 *   get      { sessionID } -> Session.Info (the session's `outcome` is idle state)
 *            packages/core/src/session/session.ts get()
 *   interrupt{ sessionID } -> void; silences the current execution
 *            packages/core/src/session/execution.ts interrupt()
 *   hook     session.hook("context" | "prompt" | ...)
 *            packages/plugin/src/promise/session.ts SessionHooks
 */
export const SESSION_DOMAIN = Object.freeze({
  methods: ["create", "prompt", "synthetic", "wait", "get", "interrupt", "hook"],
  source: "packages/plugin/src/promise/session.ts, packages/client/src/effect/api/api.ts",
  completionEvent: "session.execution.succeeded | failed | interrupted",
  completionSource: "packages/schema/src/session-event.ts SessionEvent.Execution",
});

export function hostFacts() {
  return Object.freeze({
    version: HOST_VERSION,
    pluginApi: PLUGIN_API,
    configEntry: CONFIG_ENTRY,
    agents: AGENTS,
    tools: TOOL_NAMES,
    managerForbiddenTools: MANAGER_FORBIDDEN_TOOLS,
    planForbiddenTools: PLAN_FORBIDDEN_TOOLS,
    workerHostTools: WORKER_HOST_TOOLS,
    hooks: HOOKS,
    delivery: DELIVERY,
    msPerIntentByte: MS_PER_INTENT_BYTE,
    subagentDepth: SUBAGENT_DEPTH,
    toolOptions: TOOL_OPTIONS,
    sessionDomain: SESSION_DOMAIN,
  });
}
