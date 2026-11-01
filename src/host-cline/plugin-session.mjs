// The session request the host needs for this plugin to reach the model
// (根 AGENTS.md §7.1, §7.2).
//
// Two separate facts decide whether the plugin's tools are in a session, and the
// host source is explicit about both:
//
//   1. LOADING. The session loads plugins from `config.pluginPaths`
//      (`services/local-runtime-bootstrap.ts:462` passes `localConfig?.pluginPaths`
//      to `resolveAndLoadAgentPlugins`). `config.agentPluginPaths` is a different
//      field: it feeds the *package* loader used by the settings inspector
//      (`:517`), never the session's extension list. A session request that sets
//      only `agentPluginPaths` therefore loads no plugin, and the model sees the
//      builtin set only.
//
//   2. VISIBILITY. Loaded plugin tools are added to the session's extension list
//      (`:552` `filterExtensionToolRegistrations(loadedPlugins?.extensions)`), and
//      the runtime then filters them by `toolPolicies`
//      (`runtime/orchestration/runtime-builder.ts:85-99`). A policy entry with
//      `enabled: false` removes the tool from the request; there is no other
//      narrowing knob. `plugins` selects *which plugins* load (worker mode only).
//
// So this module computes the request fields for one session role:
//
//   - `pluginPaths: [entry]` — the plugin must load;
//   - `toolPolicies` — every builtin disabled and every face enabled, so the
//     request carries exactly this plugin's tools (§7.2: "可见集合不是执行授权"
//     still holds — `hooks.beforeTool` remains the gate, and a role that may not
//     use a face is refused there even though the face is visible);
//   - `plugins` — the plugin is selected by name where the host honours it.
//
// Nothing here guesses: each field is one the host reads, and a field the host
// does not read is not set.

// The host's builtin tool names (`extensions/tools/definitions.ts`,
// `runtime-builder.ts` CONFIGURED_AGENT_TOOL_NAME_ALIASES). Disabling them is
// what makes the request carry only this plugin's face.
export const BUILTIN_TOOLS = Object.freeze([
  "read_files",
  "search_codebase",
  "run_commands",
  "fetch_web_content",
  "apply_patch",
  "editor",
  "skills",
  "spawn_agent",
  "team_run_task",
  "team_await_runs",
  "ask_question",
  "submit_and_exit",
]);

// Every tool this plugin registers. A session request enables exactly these.
export const PLUGIN_TOOLS = Object.freeze(["js-manager", "js-worker", "js-plan", "js-review", "fork", "join"]);

// The tools a Plan session may use (§3.1, §6): its own face, and nothing else.
// Plan has no `js-review`: review is the Manager's evaluation phase, and a Plan
// that could judge a relay would be a second top-level role.
export const PLAN_VISIBLE_TOOLS = Object.freeze(["js-plan"]);

// The tools a Manager session may use (§5): the Manager face plus the two
// top-level-only verbs. `js-worker`/`js-plan` are enabled but refused by
// `hooks.beforeTool` — see `surfaceAllowed`.
export const MANAGER_VISIBLE_TOOLS = Object.freeze(["js-manager", "js-review", "fork", "join"]);

// The plugin machinery a started session must have switched on, or the loader
// is never reached (`local-runtime-bootstrap.ts`). The host reads this field as
// an ARRAY: `localRuntime.configExtensions?: RuntimeConfigExtensionKind[]`
// (`@cline/shared` `hub.d.ts:369`, `RuntimeConfigExtensionKind =
// "rules" | "skills" | "workflows" | "plugins"`), and its own helper does
// `new Set(extensions).has(kind)`. An object form (`{ rules: true, ... }`)
// therefore throws `object is not iterable` inside the host — measured on
// `@cline/core` 0.2.0 — so every session request this adapter builds carries
// this array, and nothing else.
export const CONFIG_EXTENSIONS = Object.freeze(["rules", "skills", "plugins"]);

// The face of each session role, keyed by the role name the host's sessions
// carry. A role absent from this table is refused, never defaulted.
export const VISIBLE_TOOLS = Object.freeze({
  plan: PLAN_VISIBLE_TOOLS,
  manager: MANAGER_VISIBLE_TOOLS,
});

// A policy table that disables every tool not named in `enabled`.
//
// `isToolEnabledByPolicies` merges `toolPolicies["*"]` with the tool's own
// entry and treats anything but `enabled: false` as enabled, so the global entry
// must be the explicit `false` and each wanted tool must carry its own `true`.
export const toolPoliciesFor = (enabled) => {
  const policies = { "*": { enabled: false } };
  for (const name of enabled) policies[name] = { enabled: true };
  return policies;
};

// The whole request's plugin-related fields, for one session role.
//
//   `pluginPaths`   the entry the host must load (a `.js` file; `.mjs` is
//                   refused by the loader's extension check)
//   `toolPolicies`  only the role's face is visible
//   `plugins`       the plugin selected by name where the host honours it
//
// `role` must be `"plan"` or `"manager"`. There is no default and no fallback:
// an unknown role would silently get the Manager face, and a request built for
// a role nobody meant is worse than a refusal (spec §7.2: the visible set is a
// decision, not a guess).
export const pluginSessionConfig = ({ entry, role } = {}) => {
  if (typeof entry !== "string" || entry.length === 0) {
    return { ok: false, reason: "PLUGIN_ENTRY_MISSING" };
  }
  const visible = VISIBLE_TOOLS[role];
  if (visible === undefined) {
    return { ok: false, reason: "UNKNOWN_SESSION_ROLE", detail: role ?? null };
  }
  return {
    ok: true,
    pluginPaths: [entry],
    configExtensions: [...CONFIG_EXTENSIONS],
    toolPolicies: toolPoliciesFor(visible),
    plugins: { "*": { enabled: true }, herdchestra: { enabled: true } },
    visible,
  };
};
