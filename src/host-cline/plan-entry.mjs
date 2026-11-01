// The operator's entry to a planning task (根 AGENTS.md §6).
//
// Plan is a session of its own, and this file is the only place that starts it.
// The entry does not invent a channel: it starts the session on the host's own
// session factory, with this plugin loaded again and marked as a Plan, and sends
// the operator's text as that session's first prompt.
//
// Two callers exist, and both reach `startPlan`:
//
//   - a CLI or an SDK script embedding Cline:
//       const cline = await ClineCore.create();
//       const { startPlan } = await import("<repo>/src/host-cline/index.js");
//       const started = await startPlan({ host: cline, text: "..." });
//   - a session that already runs this plugin (`plugin.openPlan(text)`), which
//     uses the host handle its own session factory opened.
//
// A host that cannot start a session answers `{ ok: false, reason }`; the entry
// never reports a session that does not exist.

import { ROLES } from "../compose/program/surface.mjs";
import { PLAN_PROMPT } from "../compose/prompt/plan.mjs";
import { ROLE_METADATA_KEY } from "./identity.mjs";
import { ensureDraftDir, planDraftPath } from "./drafts.mjs";
import { sessionIdFor, writeRoleFact } from "./role-fact.mjs";
import { safeName } from "./facts.mjs";
import { pluginEntryPath } from "./entry-path.mjs";
import { pluginSessionConfig } from "./plugin-session.mjs";
import { refusalFromStart } from "./start-result.mjs";

// `startPlan({ host, text, root, name, providerId, modelId })`.
//
//   - `host` is a `ClineCore` (`start`) or a `RuntimeHost` (`startSession`);
//   - `text` is the operator's request; it is the session's first prompt, so the
//     planning session begins from the operator's own words (§6: Plan starts
//     from the task, not from a summary);
//   - `root` is the project root; the draft path is derived from it and the
//     session name, and the rule registered with `js-plan` names it to the model.
// The approval round-trip a started session asks through (spec §5.1).
//
// The plugin's setup context does NOT carry `requestToolApproval` (measured:
// `ctxKeys` has no such field, in the sandbox and in-process alike). The host
// does accept one on the start request: the hub's `session.create` puts the
// callback it is handed into the session's capabilities
// (`capabilities: { toolExecutors, requestToolApproval }`), and that is where an
// agent runtime reads it from.
//
// A start request is serialised on its way to the hub, so the callback travels
// as `approval: { channel, sessionId }` and the answer comes back through the
// channel the caller owns (an event, a queue, a file). The plugin only ever sees
// the requester the host hands to `setup`; it never reaches the hub itself.
export const startPlan = async ({
  host,
  text,
  root = process.cwd(),
  name = null,
  providerId = "cline",
  modelId = "",
  credentials = null,
} = {}) => {
  if (host === null || typeof host !== "object") {
    return { ok: false, reason: "HOST_HAS_NO_SESSION_CREATE" };
  }
  // The capability is checked before the arguments: an object with no session
  // factory cannot start anything, and reporting a missing argument instead
  // would send the caller to the wrong fix.
  const starts =
    typeof host.start === "function"
      ? host.start.bind(host)
      : typeof host.startSession === "function"
        ? host.startSession.bind(host)
        : null;
  if (starts === null) {
    return { ok: false, reason: "HOST_HAS_NO_SESSION_CREATE" };
  }
  if (typeof text !== "string" || text.trim().length === 0) {
    return { ok: false, reason: "PROMPT_MISSING" };
  }
  // The host's `CoreModelConfig` requires a non-empty model id; a request without
  // one fails inside the host's schema validation, where the reason is buried in
  // a stringified zod array. Refuse it here, where the caller can act on it.
  if (typeof modelId !== "string" || modelId.trim().length === 0) {
    return { ok: false, reason: "MODEL_MISSING", message: "startPlan needs the model the host session runs" };
  }
  const sessionName = safeName(name ?? `plan-${Date.now().toString(36)}`);
  const draft = planDraftPath(root, sessionName);
  // The draft's directory exists before the session can write into it. The
  // atomic write cannot create it, so a fresh workspace would fail every write
  // with ENOENT and then, once the model made `drafts.md` a plain file, with
  // ENOTDIR. A directory that cannot be created is the same class of refusal as
  // a role fact that cannot be written.
  const draftDir = ensureDraftDir({ root, file: draft });
  if (draftDir.ok !== true) {
    return { ok: false, reason: draftDir.reason, message: draftDir.detail ?? null };
  }
  // The session id is OURS, chosen before the host is asked to start anything.
  // The host uses a caller's id verbatim and hands it to the plugin's setup
  // context, while the plugin is set up before the session is registered — so a
  // fact keyed by this id is readable on the plugin's very first hook, in the
  // sandbox too (`role-fact.mjs`).
  const sessionId = sessionIdFor(ROLES.PLAN, sessionName);
  const roleFact = writeRoleFact({ root, sessionId, role: ROLES.PLAN, draft });
  if (roleFact.ok !== true) {
    // A session that cannot be told it is a Plan would run as the default and
    // refuse its own face. Refusing here is the honest outcome.
    return { ok: false, reason: roleFact.reason, message: roleFact.detail ?? null };
  }
  // The plugin must LOAD (localRuntime.pluginPaths, not config) and its face
  // must be VISIBLE (toolPolicies). Both are the host's own fields; see
  // `plugin-session.mjs` for the source of each.
  const plugins = pluginSessionConfig({ entry: pluginEntryPath(), role: ROLES.PLAN });
  if (plugins.ok !== true) return { ok: false, reason: plugins.reason };
  const request = {
    config: {
      providerId,
      modelId,
      // The host's own stored credential for this provider. A Plan started with
      // only `{ providerId, modelId }` reaches the provider and dies on "You
      // didn't provide an API key" (measured live on Desktop). A caller that
      // supplies its own provider config passes nothing here.
      ...(credentials === null || credentials === undefined ? {} : { ...credentials, modelId }),
      // The id the role fact is keyed by. Without it the host mints its own id
      // and the plugin cannot find the fact it was meant to read.
      sessionId,
      cwd: root,
      workspaceRoot: root,
      // §6: Plan is outside the five-segment projection; its prompt is its own
      // role segment and the host keeps a non-empty `systemPrompt` as written.
      systemPrompt: PLAN_PROMPT,
      enableTools: true,
      enableSpawnAgent: false,
      enableAgentTeams: false,
      toolPolicies: plugins.toolPolicies,
    },
    // The plugin loader reads `pluginPaths` from the LOCAL runtime config, not
    // from `config`: `local-runtime-bootstrap.ts:463` passes
    // `localConfig?.pluginPaths` to `resolveAndLoadAgentPlugins`, and
    // `localConfig` is built from `localRuntime` (`:356-360`). A request that
    // puts the paths in `config` loads no plugin at all — the session then runs
    // the bare system prompt with no face and no herdchestra rule, which is
    // exactly what the live host did before this move.
    localRuntime: {
      pluginPaths: plugins.pluginPaths,
      // The plugin machinery must be on, or the loader is never reached. The
      // field is an array on this host (`plugin-session.mjs`
      // `CONFIG_EXTENSIONS`); the object form throws inside the host.
      configExtensions: plugins.configExtensions,
    },
    // Which plugins this session uses (host: `StartSessionInput.plugins`).
    plugins: plugins.plugins,
    source: "core",
    prompt: text,
    // The plugin of this session reads the role back from here (`identity.mjs`),
    // and the draft path from the second key, so the rule and the ledger agree
    // with the session that was actually started.
    sessionMetadata: { [ROLE_METADATA_KEY]: ROLES.PLAN, "herdchestra.draft": draft },
  };
  try {
    const started = await starts(request);
    // A start that answered without a session id is a failure, and the host's
    // own error may ride on the answer rather than be thrown (`start-result.mjs`).
    const refused = refusalFromStart(started);
    // The receipt keeps the entry's own reason; the host's finer reason travels
    // in `detail` so a caller can tell a schema refusal from a missing id.
    if (refused !== null) {
      return { ok: false, ...refused, reason: "PLAN_SESSION_FAILED", detail: refused.reason };
    }
    // The host uses the id the request carried; a host that answered with a
    // different one would leave the fact unreachable, so that is reported rather
    // than papered over with a second file.
    if (started.sessionId !== sessionId) {
      return {
        ok: false,
        reason: "SESSION_ID_CHANGED",
        sessionId: started.sessionId,
        expected: sessionId,
        draft,
        message: "the host did not start the session under the id the role fact is keyed by",
      };
    }
    // `pluginIssues` is reported only when the host reported some: an empty
    // array is not a fact worth carrying, and a receipt should say what happened.
    const pluginIssues = Array.isArray(started?.pluginIssues) ? started.pluginIssues : [];
    return {
      ok: true,
      sessionId,
      draft,
      ...(pluginIssues.length > 0 ? { pluginIssues } : {}),
    };
  } catch (error) {
    return { ok: false, reason: "PLAN_SESSION_FAILED", message: String(error?.message ?? error) };
  }
};
