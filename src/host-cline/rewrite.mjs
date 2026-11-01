// The one request-rewrite entry on Cline (根 §7.2).
//
// Root §7.2 names the entry: the request copy of every model request, and it
// names why — Cline forces `compaction.enabled = true`, and the rewrite must not
// assume the host left the conversation alone. What a PLUGIN can actually reach
// was measured on the installed `@cline/core` 0.2.0
// (`/usr/lib/Cline/bin/desktop-backend/plugin-runtime/node_modules/@cline/core`)
// and in the `../cline` source; `HACK.md` records the measurement, this module
// is the result:
//
//   1. `beforeModel`'s `messages` ARE the provider-bound copy, for this request
//      only. The runtime builds a request from its own transcript
//      (`agent-runtime.ts:1671-1690`), hands it to `prepareTurn`, then to every
//      `beforeModel` hook, and applies a returned `messages` to the local
//      `request` variable (`agent-runtime.ts:1700-1711`) — the same object
//      `model.stream` later reads (`agent-runtime.ts:2099`). Nothing writes it
//      back: the transcript grows only through `state.messages.push`
//      (`agent-runtime.ts:779,817,977,1126,1602,2314`), and the conversation
//      store is replaced from `runResult.messages`, which is the transcript
//      (`session-runtime-orchestrator.ts:1096-1100`).
//
//   2. The shape it sees is the runtime's OWN `AgentMessage[]`
//      (`@cline/shared` `agent.ts:122`: `{ id, role: "user"|"assistant"|"tool",
//      content: AgentMessagePart[] }`), not the provider's. A tool call is a
//      `{ type: "tool-call", toolCallId, toolName, input }` part of an assistant
//      message; a result is a `{ type: "tool-result", toolCallId, toolName,
//      output, isError }` part of a `role: "tool"` message. The provider
//      conversion happens AFTER the hooks, in the model adapter
//      (`apihandler-agent-model-adapter.ts:147` → `agent-message-codec.ts:237-306`).
//      So the copy is projected in that shape, and `role: "tool"` stays.
//
//   3. `registerMessageBuilder` is NOT reachable for a plugin here. A builder's
//      `build` runs inside `prepareProviderMessagesForApi`
//      (`session-runtime-orchestrator.ts:1258-1267`), which only the host's own
//      `prepareTurn` calls (`prepareMessagesForModelRequest`, line 1246), and
//      `local-runtime-host.ts:754` always passes one — so a plugin's builder
//      never runs on this host. Measured: the Pair Hint pair reached 0/24
//      provider requests while the builder was the entry.
//
//   4. The system prompt cannot be replaced by a hook. `beforeModel`'s answer is
//      read for `stop`/`messages`/`tools`/`options` only
//      (`agent-runtime.ts:1700-1719`, same in 0.2.0) — a returned
//      `systemPrompt` is DROPPED. What a plugin CAN do is register a rule
//      (`registerRule`), which the host appends to the system prompt of every
//      request (`composeSystemPrompt` → `mergeSystemPromptRules`,
//      `session-runtime-orchestrator.ts:754-770`), after the host's own
//      `config.systemPrompt`. Measured: the role rule reached 23/24 requests.
//
// So the role prompt is the plugin's rule, which every request carries, and
// `systemPromptRule` makes it follow the session's role for the whole session.
// The request copy is `requestRewrite`, called from `beforeModel`.
//
// Nothing here writes to the transcript. The rewrite returns a new array of new
// message objects and never mutates one the host owns.

import { systemPromptFor } from "../compose/prompt/projection.mjs";
import { directorPromptFor } from "../compose/prompt/director.mjs";
import { PLAN_PROMPT } from "../compose/prompt/plan.mjs";
import { ROLES } from "../compose/program/surface.mjs";

// The system prompt one role's requests carry (根 §5, §2.2).
//
// A Manager gets `COMMON + MANAGER` plus the director message; coder and devops
// get `COMMON + WORKER`, byte-identical to each other; Plan is outside the
// projection and gets its own segment. Every other role reaches
// `systemPromptFor`, which refuses: an unrecognized role must not quietly
// receive the worker prompt.
export const rolePrompt = (role) => {
  if (role === ROLES.PLAN) return PLAN_PROMPT;
  if (role === ROLES.MANAGER) return directorPromptFor(systemPromptFor(ROLES.MANAGER));
  return systemPromptFor(role);
};

// The plugin rule that IS the role prompt on this host.
//
// `prompt` is read on every request, so a session whose role is settled later
// (an operator-started session adopts its role from the first top-level call)
// carries the right prompt from the first request after that, and a session this
// adapter started carries it from its first request.
//
// A rule whose prompt cannot be produced contributes nothing rather than
// breaking the request: the host wraps a rule that throws into `""`
// (`plugin-registry.ts` `wrapRule`), and this module keeps that behaviour even
// when it is called outside the host's wrapper.
export const systemPromptRule = ({ id = "herdchestra-system-prompt", source = "herdchestra", prompt }) => ({
  id,
  source,
  content: () => {
    try {
      const text = prompt();
      return typeof text === "string" ? text : "";
    } catch {
      return "";
    }
  },
});

// One model request's copy, bound to a session's own projector.
//
// `project` is the session's request copy (`SessionState.projectMessages`): the
// relay barrier, Scope, Pair Hint, CUE rendering — every rule about WHAT the
// model reads, owned by the session and applied to the copy alone.
//
// A request that carries no message list is answered unchanged. A projector
// that throws must not take the request down with it: the host would fail the
// whole request, so the honest answer is the request the host already had.
export const requestRewrite = ({ project }) => async (messages) => {
  if (!Array.isArray(messages)) return messages;
  try {
    const projected = await project(messages);
    return Array.isArray(projected) ? projected : messages;
  } catch {
    return messages;
  }
};

// The host hook that runs the copy: `beforeModel`.
//
// The hook's answer is merged by the host with every other `beforeModel`
// (`mergeRuntimeHooks`, `session-runtime-orchestrator.ts:228-256`): the merged
// `messages` is what the next hook, and then the provider, sees. So the answer
// carries `messages` and nothing else — `appendContext` belongs to the
// session's own hooks (`hooks.mjs`), which run in the same merge.
export const REQUEST_STAGE = Object.freeze({
  // The host hook the copy is applied from.
  ENTRY: "beforeModel",
  // Where the runtime applies it, and why it reaches the provider.
  HOST_CALL_SITE: "agent-runtime.ts:1700-1711 (request.messages replaced, then model.stream(request) at 2099)",
  // Why it cannot persist: the transcript is only ever written by the runtime.
  TRANSCRIPT: "state.messages is never assigned from the hook's answer (agent-runtime.ts:779,817,977,1126,1602,2314)",
  // Why the spec's `prepareTurn` half cannot be a plugin registration.
  PREPARE_TURN_REACH: "host-owned: local-runtime-host.ts:754 passes createCompactionStateAwarePrepareTurn; no plugin surface adds to it",
});

// Whether this host carries what the rewrite needs.
//
// A hook is not a `register*` call: the host reads the hook bag off the plugin
// object itself (`wrapHooks(entry, …, extension.hooks)`,
// `plugin-registry.ts:800`), and the plugin declares the `hooks` capability
// (`index.mjs` `CAPABILITIES`). So the entry is present exactly when the plugin
// object the host will call carries `beforeModel` — which `createHooks` gives
// every plugin this adapter builds. The predicate is a reading, so a caller
// reports what happened instead of assuming it.
export const canRewrite = (plugin) => typeof plugin?.hooks?.beforeModel === "function";
