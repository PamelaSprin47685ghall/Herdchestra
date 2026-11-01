// Host sessions this adapter starts (根 AGENTS.md §6, §6.1, §7.2).
//
// Two things need a session the plugin does not own:
//
//   - a Plan session — a top-level session that loads this plugin as a Plan and
//     runs the planning face (`entry.mjs` `startPlan`);
//   - the Manager session a Plan hands off to — its first prompt is the draft
//     bytes, and it loads this plugin as a Manager, so its ledger, tools and
//     hooks are the Manager's, not the Plan's.
//
// The host gives a plugin no path from its session to another session. What it
// does give is a session factory: `ClineCore.create` / `createRuntimeHost`
// (`@cline/core`) start a session in this process. A new session loads plugins
// through `config.pluginPaths` (the field `local-runtime-bootstrap.ts:462`
// passes to the plugin loader; `agentPluginPaths` feeds the package loader and
// loads no session plugin), so a session started here points at this very entry
// file and the host's own loader builds the adapter for it.
//
// The host keeps the plugin's `setup(api, ctx)` honest: `ctx.session.sessionId`
// is the session the plugin was set up for, so one plugin module can serve
// several sessions in one process without sharing a ledger.
//
// Every failure is a receipt. A host that cannot start a session answers
// `{ ok: false, reason }`; a session that started but took no prompt is stopped
// again, so a refused hand-off leaves no half-open Manager behind.

import { ROLES } from "../compose/program/surface.mjs";
import { directorPromptFor } from "../compose/prompt/director.mjs";
import { systemPromptFor } from "../compose/prompt/projection.mjs";
import { sessionHostApi } from "./host-modules.mjs";
import { ROLE_METADATA_KEY } from "./identity.mjs";
import { startPlan } from "./plan-entry.mjs";
import { pluginEntryPath } from "./entry-path.mjs";
import { pluginSessionConfig } from "./plugin-session.mjs";
import { refusalFromStart } from "./start-result.mjs";
import { sessionIdFor, writeRoleFact } from "./role-fact.mjs";

export { ROLE_METADATA_KEY, pluginEntryPath };

// A Manager session's system prompt is the same composition as the operator's
// session: `COMMON + MANAGER` plus the director message (spec §5, §5). The
// host keeps a non-empty `systemPrompt` as written, so this is the whole prompt.
const MANAGER_PROMPT = directorPromptFor(systemPromptFor(ROLES.MANAGER));

// The start request a hand-off sends. It is a pure function of its inputs so the
// placement of each field is testable without a live host: which field the host
// reads is the whole contract, and getting it wrong is silent (a session that
// starts with no plugin).
//
// `config` carries the connection and the face; `localRuntime` carries the
// plugin paths and the plugin machinery. The two are not interchangeable:
// `local-runtime-bootstrap.ts:463` reads `localConfig?.pluginPaths`, and
// `localConfig` is built from `localRuntime` (`:356-360`).
export const managerStartRequest = ({ config, draft, localRuntime }) => ({
  config,
  source: "core",
  prompt: draft,
  // How the started session tells its plugin which role it is: the host hands
  // the plugin its session id and cwd, never a role, so the role travels as
  // session metadata (`identity.mjs`).
  sessionMetadata: { [ROLE_METADATA_KEY]: ROLES.MANAGER },
  // Which plugins this session uses (host: `StartSessionInput.plugins`).
  plugins: pluginSessionConfig({ entry: pluginEntryPath(), role: "manager" }).plugins,
  localRuntime,
});

export class ManagerSessions {
  #ctx;
  #cwd;
  #host = null;
  #opening = null;
  #started = new Set();

  /**
   * `ctx` is the plugin setup context of the session that starts the others;
   * only its provider, model, logger and telemetry are reused, because the new
   * session is the same work on the same connection.
   */
  /**
   * `model` is the connection the starting session runs, read from its own
   * model requests (`SessionState.model`); `null` means nobody has seen one yet
   * and every start from this factory is refused with `MODEL_UNKNOWN`.
   */
  constructor({ ctx = null, cwd = null, model = null, resolveModel = null, resolveCredentials = null } = {}) {
    this.#ctx = ctx;
    // The connection is read at each start, so a session that had not run its
    // first request when the factory was built still hands over the reading
    // later. `null` is a real answer: nothing has been read yet.
    Object.defineProperty(this, "model", {
      get: () => (typeof model === "function" ? model() : model),
      configurable: true,
    });
    // The same reading, awaited. `SessionState.resolveModel` also consults the
    // host's own session record, which is a promise; a caller that can wait uses
    // this so a hand-off made before the session's first model request still
    // finds the connection the host already knows.
    Object.defineProperty(this, "resolveModel", {
      value: async () => {
        if (typeof resolveModel === "function") return await resolveModel();
        const known = typeof model === "function" ? model() : model;
        return known === null || known === undefined ? { model: null, source: null } : { model: known, source: "request" };
      },
      configurable: true,
    });
    // The host's stored credentials for one connection. Injected by
    // `SessionState`, which owns the provider-settings reader; a factory built
    // without it starts sessions with the model alone (an embedder that supplies
    // its own provider config does not need this).
    Object.defineProperty(this, "credentialsFor", {
      value: async (connection) =>
        typeof resolveCredentials === "function" && connection !== null && connection !== undefined
          ? await resolveCredentials(connection)
          : null,
      configurable: true,
    });
    this.#cwd = cwd ?? ctx?.workspaceInfo?.rootPath ?? ctx?.cwd ?? process.cwd();
  }

  async available() {
    return (await sessionHostApi()) !== null;
  }

  // The host context a new session inherits. `setup` carries `client`, `user`
  // and `telemetry`, and a session started from here belongs to the same
  // operator: dropping them would make the new session anonymous to its own
  // provider settings.
  #extensionContext() {
    const context = {};
    if (this.#ctx?.client !== undefined) context.client = this.#ctx.client;
    if (this.#ctx?.user !== undefined) context.user = this.#ctx.user;
    if (this.#ctx?.telemetry !== undefined) context.telemetry = this.#ctx.telemetry;
    if (this.#ctx?.workspaceInfo !== undefined) context.workspace = this.#ctx.workspaceInfo;
    return Object.keys(context).length > 0 ? context : undefined;
  }

  // The host handle, opened once and kept: a Plan may hand off once, and the
  // handle also stops a Manager it started but could not prompt.
  async host() {
    if (this.#host !== null) return this.#host;
    if (this.#opening !== null) return await this.#opening;
    this.#opening = (async () => {
      const api = await sessionHostApi();
      if (api === null) return null;
      try {
        if (api.kind === "clineCore") {
          const core = await api.mod.ClineCore.create({
            clientName: "herdchestra",
            backendMode: "local",
            ...(this.#ctx?.telemetry === undefined ? {} : { telemetry: this.#ctx.telemetry }),
            ...(this.#ctx?.logger === undefined ? {} : { logger: this.#ctx.logger }),
          });
          return { kind: "clineCore", handle: core };
        }
        const host = await api.mod.createRuntimeHost({ backendMode: "local" });
        return { kind: "runtimeHost", handle: host };
      } catch {
        return null;
      }
    })();
    try {
      this.#host = await this.#opening;
      return this.#host;
    } finally {
      this.#opening = null;
    }
  }

  // The connection a new session reuses: the provider and model the starting
  // session runs, because a hand-off is the same work on the same account.
  // `model` is the connection the starting session actually ran
  // (`SessionState.model`). Setup carries no provider and no model, so a caller
  // that has none is refused by `startManager`/`openPlan` before this point.
  config({ systemPrompt, cwd = this.#cwd, sessionId, model, credentials = null }) {
    const plugins = pluginSessionConfig({ entry: pluginEntryPath(), role: "manager" });
    if (plugins.ok !== true) return null;
    if (model === null || model === undefined) return null;
    return {
      providerId: model.providerId,
      modelId: model.modelId,
      // The host's own stored credential for this provider. A session started
      // with only `{ providerId, modelId }` reaches the provider and dies on
      // "You didn't provide an API key" (measured live on Desktop). A host that
      // stores none is started without, and the provider's error is the report.
      ...(credentials === null || credentials === undefined ? {} : { ...credentials, modelId: model.modelId }),
      // The id the Manager's role fact is keyed by. The host uses it verbatim
      // and hands it to the plugin's setup context, which is the only way the
      // plugin can know its role before the session is registered
      // (`role-fact.mjs`).
      sessionId,
      cwd,
      workspaceRoot: cwd,
      systemPrompt,
      enableTools: true,
      // The host's own subagent tools stay off: `fork` is this adapter's, and a
      // Manager that also had `spawn_agent` would have two ways to delegate.
      enableSpawnAgent: false,
      enableAgentTeams: false,
      // The face is the only thing the request carries (`toolPolicies` disables
      // every builtin). The plugin PATH is not set here: the session loader
      // reads it from the local runtime config (`localRuntime.pluginPaths`), and
      // a `config.pluginPaths` loads nothing.
      toolPolicies: plugins.toolPolicies,
    };
  }

  // `openPlan(text, { root, name })`: the operator's entry to a planning task
  // (根 §2.2). The Plan session is a session of its own, started on the same host
  // handle a hand-off uses; its first prompt is the operator's own words.
  // `root` and `name` are the entry's own derived values (`index.mjs` fills
  // them from this session when the caller names none), and `startPlan` derives
  // the draft path and the session id from them.
  async openPlan(text, { root = this.#cwd, name = null, model = null } = {}) {
    const host = await this.host();
    if (host === null) return { ok: false, reason: "HOST_HAS_NO_SESSION_CREATE" };
    // The same rule as a hand-off: no reading, no start. `startPlan` refuses an
    // empty model, so an unknown one is reported here with its own name. The
    // reading uses the same ladder as `startManager` — last request, setup
    // context, host session record, environment.
    const resolved = model === null || model === undefined ? await this.resolveModel() : { model, source: "caller" };
    const connection = resolved.model;
    if (connection === null || connection === undefined) {
      return {
        ok: false,
        reason: "MODEL_UNKNOWN",
        message: "no source named this session's provider and model",
        detail: { tried: ["request", "context", "sessionRecord", "environment"] },
      };
    }
    return startPlan({
      host: host.handle,
      text,
      root,
      name,
      providerId: connection.providerId,
      modelId: connection.modelId,
      // The same credentials `startManager` and `fork` use; a Plan started
      // without them dies at the provider on "no API key".
      credentials: await this.credentialsFor(connection),
    });
  }

  // §6.1: the Manager a Plan hands off to. The draft bytes are the first prompt;
  // the session is independent (its own plugin load, its own ledger).
  async startManager(draft) {
    if (typeof draft !== "string" || draft.length === 0) return { ok: false, reason: "DRAFT_MISSING" };
    // Capability before arguments: a host that cannot start a session is the
    // first fact to report, not the missing model of a session that could never
    // exist.
    const host = await this.host();
    if (host === null) return { ok: false, reason: "HOST_HAS_NO_SESSION_CREATE" };
    // The host's schema needs a non-empty model id; a hand-off without one would
    // fail inside the host with an unreadable zod message. The reading is the
    // session's own connection, from its last request, its setup context, or the
    // host's own session record (`SessionState.resolveModel`) — never a guess.
    const resolved = await this.resolveModel();
    const model = resolved.model;
    const modelSource = resolved.source;
    if (model === null) {
      return { ok: false, reason: "MODEL_UNKNOWN", session: null, detail: { tried: ["request", "context", "sessionRecord", "environment"] } };
    }
    // The model alone cannot run: the host stores this provider's key and base
    // URL, and a Manager started without them dies at the provider.
    const credentials = await this.credentialsFor(model);
    // The Manager's identity is decided before anything is started: the id the
    // role fact is keyed by, and the fact itself. A session that cannot be told
    // it is a Manager is not started, because it would run as an unmarked
    // session with no ledger of its own.
    const sessionId = sessionIdFor(ROLES.MANAGER, "manager");
    const roleFact = writeRoleFact({ root: this.#cwd, sessionId, role: ROLES.MANAGER });
    if (roleFact.ok !== true) {
      return { ok: false, reason: roleFact.reason, session: null, message: roleFact.detail ?? null };
    }
    const config = this.config({ systemPrompt: MANAGER_PROMPT, sessionId, model, credentials });
    if (config === null) return { ok: false, reason: "PLUGIN_ENTRY_MISSING", session: null };
    const plugins = pluginSessionConfig({ entry: pluginEntryPath(), role: "manager" });
    if (plugins.ok !== true) return { ok: false, reason: plugins.reason, session: null };
    const extensionContext = this.#extensionContext();
    const localRuntime = {
      extensions: [],
      // The plugin loader reads its paths from HERE, not from `config`
      // (`local-runtime-bootstrap.ts:463` via `localConfig`, built from
      // `localRuntime` at `:356-360`). A Manager started without this loads no
      // plugin, so it has no face, no ledger and no hooks.
      pluginPaths: plugins.pluginPaths,
      // An array, because the host iterates it (`plugin-session.mjs`
      // `CONFIG_EXTENSIONS`); the object form throws `object is not iterable`.
      configExtensions: plugins.configExtensions,
      ...(extensionContext === undefined ? {} : { extensionContext }),
    };
    let started = null;
    try {
      const request = managerStartRequest({ config, draft, localRuntime });
      started =
        host.kind === "clineCore"
          ? await host.handle.start(request)
          : await host.handle.startSession(request);
    } catch (error) {
      // A host may have created the session and then failed on the prompt. The
      // receipt carries the id only when the host gave one, so a caller can stop
      // a half-open Manager; nothing is invented when it did not.
      return {
        ok: false,
        reason: "MANAGER_SESSION_FAILED",
        session: typeof error?.sessionId === "string" ? error.sessionId : null,
        message: String(error?.message ?? error),
      };
    }
    const refused = refusalFromStart(started);
    if (refused !== null) {
      return { ok: false, session: null, ...refused, reason: "MANAGER_SESSION_FAILED", detail: refused.reason };
    }
    // A host that answered with another id leaves the fact unreachable; stop the
    // half-open session and say so instead of starting a second identity.
    if (started.sessionId !== sessionId) {
      const stopped = await this.stopManager(started.sessionId);
      return {
        ok: false,
        reason: "SESSION_ID_CHANGED",
        session: null,
        discarded: started.sessionId,
        expected: sessionId,
        cleanup: stopped,
        message: "the host did not start the session under the id the role fact is keyed by",
      };
    }
    this.#started.add(sessionId);
    const pluginIssues = Array.isArray(started?.pluginIssues) ? started.pluginIssues : [];
    return {
      ok: true,
      sessionId,
      // Which reading supplied the connection this Manager runs on. A receipt
      // that names it can be checked against the session that produced it.
      ...(modelSource === null ? {} : { modelSource }),
      ...(pluginIssues.length > 0 ? { pluginIssues } : {}),
    };
  }

  // A Manager session that exists but took no prompt is not a Manager: stop it.
  async stopManager(sessionId) {
    const host = await this.host();
    if (host === null) return { ok: false, reason: "HOST_HAS_NO_SESSION_CREATE" };
    try {
      if (host.kind === "clineCore") await host.handle.stop(sessionId);
      else await host.handle.stopSession(sessionId);
      this.#started.delete(sessionId);
      return { ok: true, sessionId };
    } catch (error) {
      return { ok: false, reason: "STOP_FAILED", message: String(error?.message ?? error) };
    }
  }

  get started() {
    return [...this.#started];
  }
}
