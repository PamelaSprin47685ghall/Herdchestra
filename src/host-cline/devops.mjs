// The devops runtime a Plan starts (根 AGENTS.md §6).
//
// §6 gives Plan one way to learn repository facts: `this.devops(intent)`, which
// runs a devops runtime synchronously and returns its complete investigation
// record — the runtime's own text, not a summary. This module owns that one
// runtime and its admission rule:
//
//   - at most one unresolved devops per task. A second call while one is in
//     flight is refused (`DEVOPS_ALREADY_IN_FLIGHT`), not queued;
//   - the runtime is started through the same delegated factory `fork` uses
//     (`@cline/core` `createDelegatedAgent`), with the worker's own system
//     prompt (`COMMON + WORKER`) and the tools the caller gives it — the worker
//     face, `call` included, because a devops without `call` cannot run
//     anything;
//   - a host that does not hand the factory over answers `HOST_HAS_NO_DELEGATED_AGENT`,
//     and a runtime that fails answers `DEVOPS_FAILED`. Neither invents a report.
//   - the connection comes from `resolveModel` — the session's last request, its
//     setup context, the host's session record, or the environment — never from
//     a default. A devops runtime started on `providerId: "cline"` with an empty
//     model would fail inside the host with a connection error the caller could
//     not read, so an unknown connection is refused here by name.
//
// The factory is resolved at run time and injected into `runtimeHandle`, so this
// module never imports the host.

import { ROLES } from "../compose/program/surface.mjs";
import { systemPromptFor } from "../compose/prompt/projection.mjs";
import { guardedDelegatedApi } from "./host-modules.mjs";
import { runtimeHandle } from "./runtime.mjs";

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
  return {
    get busy() {
      return inFlight !== null;
    },
    // `investigate(intent)` answers `{ ok: true, report }` or
    // `{ ok: false, reason, message? }`.
    async investigate(intent) {
      if (inFlight !== null) return { ok: false, reason: "DEVOPS_ALREADY_IN_FLIGHT" };
      // The guarded factory (root §7.2): the devops face survives a narrowed
      // tool list exactly as a forked worker's does.
      const delegated = await delegatedApi(() => workerTools());
      if (delegated === null) return { ok: false, reason: "HOST_HAS_NO_DELEGATED_AGENT" };
      // The same ladder `fork` and the hand-off use. A devops run is the same
      // work on the same account as the Plan that asks for it.
      const resolved =
        typeof resolveConnection === "function"
          ? await resolveConnection()
          : {
              model: ctx?.providerId !== undefined && ctx?.modelId !== undefined ? { providerId: ctx.providerId, modelId: ctx.modelId } : null,
              source: "context",
              credentials: null,
              credentialSource: null,
            };
      const connection = resolved?.model ?? null;
      if (connection === null || typeof connection.modelId !== "string" || connection.modelId.length === 0) {
        return {
          ok: false,
          reason: "MODEL_UNKNOWN",
          message: "no source named this session's provider and model, so the devops runtime cannot connect",
          detail: { tried: ["request", "context", "sessionRecord", "environment"] },
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
      const handle = runtimeHandle({
        name: `plan-devops-${Date.now().toString(36)}`,
        // The identity is stated in the brief, exactly as §2.1 requires of any
        // subagent start; the `devops` name here is the runtime handle's label,
        // not the identity.
        brief: `role: devops — ${intent}`,
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
      if (handle?.started === false) {
        return { ok: false, reason: handle.rejection ?? "HOST_HAS_NO_DELEGATED_AGENT", message: handle.message };
      }
      inFlight = new Promise((resolve) => {
        handle.onSettled((record) => resolve(record));
      });
      try {
        const record = await inFlight;
        if (record?.finishReason === "error") {
          return { ok: false, reason: "DEVOPS_FAILED", message: record.text };
        }
        return { ok: true, report: record?.text ?? "" };
      } finally {
        inFlight = null;
      }
    },
  };
};
