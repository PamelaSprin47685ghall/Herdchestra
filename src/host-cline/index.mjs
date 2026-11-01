// Plugin entry (Cline plugin form, AGENTS.md §7.2).
//
// The plugin object is `{ name, manifest, hooks, setup }`. `setup(api, ctx)`
// builds the adapter for one session, registers the six tools and the two rules,
// and installs the hooks the host calls.
//
// Prompts come from compose (`COMMON + MANAGER`, `COMMON + WORKER`, Plan's own
// segment); this directory keeps no copy and does not re-concatenate them.
//
// The host loads the plugin once per session: `AgentExtension.setup` is called
// during contribution-registry setup, and the hooks object is read from the same
// extension (`mergeRuntimeHooks`), so one setup owns one session's state. The
// host does not say which role a session plays; `setup` reads that back from the
// session's own metadata (`identity.mjs`), which is how a session this adapter
// started becomes a Plan or a Manager.

import { createAdapter } from "./adapter.mjs";
import { createOperatorSeam } from "./operator.mjs";
import { SessionState } from "./session.mjs";
import { ROLES } from "../compose/program/surface.mjs";

export const PLUGIN_NAME = "herdchestra";

// The host validates every `register*` against these capabilities
// (`AgentExtensionCapability`, `contribution-registry.ts:223-233`).
//
// `hooks` is the request rewrite's capability (根 §7.2): the copy is the
// `beforeModel` hook's `messages`. `messageBuilders` is not requested — a
// plugin's builder never runs on this host, because the host's own
// `prepareTurn` owns that stage (`rewrite.mjs` records the measurement).
export const CAPABILITIES = Object.freeze(["tools", "hooks", "rules"]);

// `role` is the session's own role: one plugin load is one host session, and a
// Plan session keeps a different ledger from a Manager session (spec §6, §7).
export function createPlugin({ role = ROLES.MANAGER, openPlan = null } = {}) {
  const plugin = {
    name: PLUGIN_NAME,
    manifest: { capabilities: [...CAPABILITIES] },
    // Filled by `setup`; the host reads `extension.hooks` from this object.
    hooks: {},
    // The tools a delegated runtime receives. The host's subagent factory takes
    // the list from its caller, so this is how a worker gets the Herdchestra
    // face and nothing of the host's builtin set.
    tools: [],
    setup(api, ctx) {
      // The session this setup owns; the seam is installed on it below.
      const state = new SessionState({ ctx, role });
      // The operator seam. `this.ask(question)` is `this.park({ question })` +
      // `interrupt`: the program parks, the tool result carries `interrupt:
      // true`, the run ends, and the operator replies in ordinary chat — which
      // `SessionState.resumeAsked` turns into the parked expression's value.
      // That channel exists on every session, so the seam never refuses for a
      // missing one (AGENTS.md §5.1). `setup` still passes whatever extra
      // channel it can see (`requestToolApproval`, `steer`); each only makes an
      // answer arrive sooner.
      const operator = createOperatorSeam({
        requestApproval: typeof ctx?.requestToolApproval === "function" ? ctx.requestToolApproval : null,
        steer: typeof ctx?.emitEvent === "function" ? (sessionId, prompt) => ctx.emitEvent("steer_message", { sessionId, prompt }) : null,
        sessionId: ctx?.session?.sessionId ?? null,
      });
      // The seam is installed on the session the adapter will own, so a port
      // built later (`ports()` caches) reads this seam and not a default.
      state.setOperator(operator);
      const adapter = createAdapter({ ctx, role, operator, state });
      adapter.register(api);
      plugin.tools = adapter.tools;
      Object.assign(plugin.hooks, adapter.hooks);
      plugin.adapter = adapter;
      // The plugin object IS the hook bag the host reads (`wrapHooks`), so the
      // request rewrite is reachable from here on.
      adapter.attachExtension(plugin);
      // The host's own end-of-session hook (`PluginSetupContext.onDispose`,
      // `contribution-registry.ts:219`). Every runtime this session started is a
      // real session in the host's process, and a Hub plugin shares that process
      // across sessions; without this, a worker keeps producing turns after the
      // session that asked for it is gone (根 §7.2).
      if (typeof ctx?.onDispose === "function") {
        ctx.onDispose(() => {
          adapter.session.release();
        });
      }
      // The role is a fact of the session, not of this module: a session this
      // adapter started carries it in its metadata, and the adapter reads it
      // back before the first hook (`adapter.identity`).
      plugin.identity = adapter.identity;
    },
  };
  // `openPlan(text, { root, name, model })` — the operator's entry to Plan (根 §2.2).
  // It starts a Plan session on the host (this plugin loaded again, marked as a
  // Plan, with `PLAN_PROMPT`) and sends `text` as its first prompt. The host
  // handle is the one the adapter's session factory opened; a caller that
  // supplies its own host passes `openPlan` when creating the plugin.
  plugin.openPlan = async (text, options = {}) => {
    const adapter = plugin.adapter;
    if (adapter === undefined) return { ok: false, reason: "PLUGIN_NOT_SET_UP" };
    // Both branches hand the entry the SAME options: the caller's own values,
    // this session's root and name where the caller named none, and the adapter
    // that owns the host handle. A caller-supplied entry that received raw
    // options would resolve a different root than the default one and could
    // start a Plan in the wrong workspace.
    const forwarded = {
      ...options,
      root: options.root ?? adapter.session.root,
      name: options.name ?? adapter.session.sessionId,
      adapter,
    };
    if (typeof openPlan === "function") return openPlan(text, forwarded);
    // The model is NOT read here: the setup context carries no provider and no
    // model (`@cline/core` 0.2.0 hands over `session`, `client`, `user`,
    // `workspaceInfo`, `cwd`, `emitEvent`, `onDispose`), so this session has not
    // run a request yet and its own reading is `null`. A caller that knows the
    // connection passes `model`; without one the entry refuses with
    // `MODEL_UNKNOWN` rather than starting a Plan on a guessed model.
    //
    // The factory receives the same derived root and name, not the raw options:
    // `startPlan` derives the draft path and the session id from them, and a
    // second call to `openPlan` must land on the same draft as the first.
    return adapter.session.sessions.openPlan(text, forwarded);
  };
  return plugin;
}

const plugin = createPlugin();

export default plugin;
export { plugin };
// The operator's entry to a planning task (根 §2.2): `startPlan({ host, text })`.
export { startPlan } from "./plan-entry.mjs";
// The role a session plays and where that fact came from (§7.2).
export { ROLE_METADATA_KEY, roleOf } from "./identity.mjs";
