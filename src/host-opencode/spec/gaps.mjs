// Open questions of the OpenCode v2 adapter (AGENTS.md §7.1, §8).
//
// Each entry is a fact the host source does not settle. The adapter must not
// pretend it is settled: a member whose whole effect depends on an open
// question refuses with the question named, instead of inventing an outcome.
//
// `evidence` says what the source does show, so a later reader can re-check
// quickly. `blocks` lists the members or behaviours that wait on it.

export const GAPS = Object.freeze([
  Object.freeze({
    id: "WORKER_NESTING",
    question:
      "Can a worker session start a new program session without the builtin subagent depth check?",
    evidence:
      "packages/core/src/tool/plugin/subagent.ts: depth is computed by walking parentID and compared with experimental.subagent_depth (default 1); the limit lives only in that tool.",
    consequence:
      "Herdchestra's fork creates sessions with Session.create and does not go through the subagent tool, so the limit does not apply. Depth beyond 1 is therefore untested, not refused.",
    blocks: [],
  }),
  Object.freeze({
    id: "LOCAL_PLUGIN_ENTRY",
    question: "Does a configured local plugin target load a package directory with a `main` field?",
    evidence:
      "packages/plugin/src/host.ts resolve(): a directory resolves `index` or `server` by path, or a package name by its exports; `main` is not consulted. A directory holding only `plugin.mjs` is silently never imported.",
    consequence:
      "Closed: the directory carries `index.js`, which re-exports the Effect entry. A local `opencode.json` target must be the directory (or `index.js`), never `plugin.mjs`.",
    blocks: [],
  }),
  Object.freeze({
    id: "PLUGIN_MODULE_SHAPE",
    question: "Which plugin shape does the host decode, and which context does each shape receive?",
    evidence:
      "packages/core/src/plugin/module.ts decodes `{ id, effect }` or `{ id, setup }`. The Effect context (packages/plugin/src/effect/plugin.ts) has agent/event/session/tool/location/options. The Promise context (packages/plugin/src/promise/adapter.ts) also builds session/tool/event, but the installed CLI 1.19.4 is a v1-plugin host: it requires `server()` and gives no session/tool/event domain at all.",
    consequence:
      "The adapter's loaded entry is `{ id, effect }` (spec/effect-entry.mjs) and bridges the Effect domains to the Promise calls the rest of the directory uses (spec/effect-adapter.mjs). The Promise `setup` remains exported for tests only. A v1 host cannot run this adapter.",
    blocks: [],
  }),
  Object.freeze({
    id: "CONFIG_KEY",
    question: "Which configuration key names a plugin on the host that runs it?",
    evidence:
      "The v2 schema names `plugins` (string or `{ package, options }`); the installed 1.19.4 host reads the legacy `plugin` key and drops `plugins` with a V1 compatibility warning.",
    consequence:
      "On a v2.1.4 host the entry is a `plugins` array holding the plugin directory; on the v1 CLI nothing loads this adapter whatever the key.",
    blocks: [],
  }),
  Object.freeze({
    id: "PROMPT_TURN_ID",
    question: "Does a tool call carry a model-turn identity the adapter can use for Scope bookkeeping?",
    evidence:
      "packages/plugin/src/promise/tool.ts ToolContext has sessionID, agent, messageID and call id; no turn id.",
    consequence:
      "The adapter keys a Scope turn by the assistant messageID seen in `tool.hook(\"execute.after\")`; a step boundary inside one message is not visible.",
    blocks: ["scope-settle"],
  }),
  Object.freeze({
    id: "PROMPT_ANNOTATION_TRANSPORT",
    question: "Where does the model write the `scope` annotation so `execute.before` can read it unmodified?",
    evidence:
      "packages/core/src/tool.ts runs execute.before before input decoding; `session.hook(\"prompt\")` sees only the text the client sent.",
    consequence:
      "The annotation is read from the tool call input object under the `scope` key. A model that writes it elsewhere is refused as SCOPE_ANNOTATION_MISSING.",
    // Observed on the real host (live6, 2026-11-01): with a free-form
    // `{ type: "object" }` the model wrote `{"kind":"resetScope"}` and every
    // call was refused as SCOPE_REFUSED. The field's shape is now declared
    // (spec/scope-schema.mjs), so the host's own input decode names the missing
    // field before the call runs. The decode runs BEFORE execute.before, but the
    // hook still receives the raw input, so the book keeps deciding.
    blocks: ["scope-admit"],
  }),
  Object.freeze({
    id: "SYNTHETIC_TOOL_PAIR",
    question: "Does the provider adapter accept an empty tool name for the Pair Hint synthetic pair?",
    evidence:
      "packages/core/src/session/runner/to-llm-message.ts builds tool calls from stored tool messages; there is no empty-name tool message in the host's own history.",
    consequence:
      "The hint is injected as the spec's pair — an assistant tool call with the empty tool name and its tool result — appended to the request copy. Whether a provider accepts an empty tool name is unverified; the pair is built from the stored fact, so a rebuild yields the same ids and bytes.",
    blocks: [],
  }),
  Object.freeze({
    id: "ASK_CHANNEL",
    question: "Which host surface turns a question into a user answer that resumes the program?",
    evidence:
      "packages/core/src/tool/plugin/question.ts asks through Form.Service and returns answers as tool output; there is no promise a program can be parked on. `Session.synthetic` admits a message with `resume: false` and returns at once.",
    consequence:
      "`ask` parks the program, records the pending question per session and shows it with `Session.synthetic`. The operator's next prompt is taken as the answer, written into the park site (an `ask` site takes its own result, not the injected value) and delivered with `unpark`, which resumes the suspended body.",
    blocks: [],
  }),
  Object.freeze({
    id: "PROMPT_HOOK_PAYLOAD",
    question: "What does `session.hook(\"prompt\")` receive?",
    evidence:
      "Closed by the host source: packages/core/src/session/prompt.ts `prepare()` triggers the hook with `{ sessionID, messageID, prompt: { text, files?, agents?, skills? }, metadata?, delivery }`, and then admits the (possibly replaced) `event.prompt`. packages/plugin/src/promise/session.ts types it as `SessionPrompt`.",
    consequence:
      "The adapter reads `event.prompt.text` and nothing else. A payload without `prompt.text` is treated as a host change: the pending `ask` stays pending and the hook answers `{ answered: false, reason: \"PROMPT_HOOK_PAYLOAD\" }` instead of closing the question with an empty answer.",
    blocks: [],
  }),
  Object.freeze({
    id: "FISSION_PRESENT",
    question: "Can one worker identity have several live sessions and replace its physical present?",
    evidence:
      "A session is one conversation with one agent; `Session.interrupt({ sessionID })` silences the current execution (packages/core/src/session/execution.ts), and `Session.create` opens a new session with a chosen agent.",
    consequence:
      "`fission` builds one child session per lane with the owner's agent, persists the group as one record, starts every lane, then interrupts the owner session. The identity survives in its lanes; admission, rollback, convergence and merge are compose's `Fission` (`compose/fission/fission.mjs`). What the host does not show is whether an interrupted session that is later prompted resumes: the owner is therefore never prompted again by the adapter.",
    blocks: [],
  }),
  Object.freeze({
    id: "FORK_SESSIONS",
    question: "Is the host session API reachable from a tool executor?",
    evidence:
      "ctx.session in the Promise plugin context is the SessionDomain (create/prompt/synthetic/wait/get/interrupt); a tool executor only receives sessionID, agent, messageID, id, signal.",
    consequence:
      "The plugin keeps the session domain from setup and hands it to the activation, so fork/join/fission/ask work without a per-call API. A call with no session domain refuses with this gap instead of failing.",
    blocks: [],
  }),
  Object.freeze({
    id: "PLAN_ENTRY",
    question: "Is Plan a role the adapter must register now?",
    evidence:
      "Root AGENTS.md §6, §7 describe Plan, but §2.1 lists only Manager, coder and devops, and §7.1 names only those three agents.",
    consequence:
      "The adapter registers a fourth agent, `Plan` (mode `primary`), with the `plan` face (§6). §6 puts Plan outside the five `skillDocuments` projection, so its system prompt is its own role segment (compose/prompt/plan.mjs), never `common + manager` or `common + worker`; `systemPromptFor(\"plan\")` refuses with PLAN_OUTSIDE_PROJECTION. It is never forked, has no `fork`/`join`, and its `review` side effects are the relay host. §7.1's three-agent list is narrower than §6; this adapter follows §6 and records the discrepancy here.",
    blocks: [],
  }),
  Object.freeze({
    id: "RELAY_SESSION_CONTROL",
    question: "Can a plugin retire a Manager session, cut its context and open a successor as one record?",
    evidence:
      "`Session.create({ parentID })` inherits the family and with it the transcript; `Session.interrupt` only stops the current execution; there is no API that hides a parent's history from a child.",
    consequence:
      "The host gives no single record, so the ADAPTER keeps one and enforces it (relay-record.mjs, relay-host.mjs, relay-facts.mjs): the successor is created inert, the record is written atomically before the brief, the brief carries a deterministic message id that the host's durable admission table reconciles (`packages/core/src/session/inbox.ts` `reconcile`, a database lookup), and both gates read the record — `execute.before` refuses a retired tenure, `session.hook(\"context\")` refuses its requests and cuts a successor's copy at the barrier. A crash before the record leaves the incumbent in office; a crash after it leaves a successor that receives the same brief under the same id. Two limits remain and are reported, not hidden: the barrier trims the successor's request copy, not the history the host already stored for it, and a refused request is booked by the host as a failed execution rather than skipped silently. OMP is not covered: its `Lane.start` takes plain text with no id, so the record carries `briefId: null` there and `recoverRelay` answers `undeliverable`.",
    blocks: ["review"],
  }),
  Object.freeze({
    id: "CONTEXT_HOOK_FAILURE",
    question: "How does a plugin stop a model request from `session.hook(\"context\")`?",
    evidence:
      "Closed by the host source: `packages/core/src/plugin/hooks.ts` `trigger` runs `entry.callback(event)` with `yield*` and has no failure channel (`SessionHooks` maps to `never`); `packages/core/src/session/execution.ts` turns a failed drain into `Failed to drain Session` and records a failed execution. There is no return value that cancels a request.",
    consequence:
      "Every §6.1 failure — a hint that cannot be opened, a resource pair with a missing book, an unbound language, a prefix that would drift, a projection core refuses — is thrown as `ContextRefusal`. The host fails the execution and no request reaches the provider; the adapter never falls back to the host's unprojected messages, because that would silently drop §6.1.",
    blocks: [],
  }),
  Object.freeze({
    id: "MESSAGE_SHAPE",
    question: "Which shape does `session.hook(\"context\")` receive in `event.messages`?",
    evidence:
      "Closed by the host source: `packages/ai/src/schema/messages.ts` defines `Message` with parts `{type:\"tool-call\", id, name, input}` and `{type:\"tool-result\", id, name, result}` where `result` is a tagged `ToolResultValue` (`json` | `text` | `error` | `content`). `packages/core/src/session/model-request.ts` `replaceMedia` reads `part.result.type` without a guard.",
    consequence:
      "The adapter translates twice (spec/message-adapter.mjs): host parts to the hint's `toolName`/`toolCallId`/`output` shape and back, restoring the host's key order so the hint's byte comparison sees the host's own bytes. A `tool-result` without `result` is wrapped as a text result instead of being forwarded, because forwarding it crashes the whole model request.",
    blocks: [],
  }),
  Object.freeze({
    id: "HINT_PAIR_PLACEMENT",
    question: "Does the synthetic Pair Hint pair come back in the host's stored history?",
    evidence:
      "The pair exists only in the request copy (`session.hook(\"context\")`); the host stores the model's own messages, not the plugin's projection, so the next request is rebuilt without it.",
    consequence:
      "Every request is a placement, not a replay: the hint places the pair at the position its persisted fact records, and the adapter first removes any pair the copy already carries so one request never holds two.",
    blocks: [],
  }),
  Object.freeze({
    id: "DRAFT_PATH",
    question: "Where does Plan's draft live, and who fixes the path?",
    evidence:
      "Root AGENTS.md §2.2: the draft's path is derived from the task and the system tells Plan the exact path; the adapter has no task metadata to derive it from.",
    consequence:
      "The `draft` port names `.Herdchestra/drafts/<session>.md`, derived from the session exactly as `host-cline` (`drafts.mjs`) and `host-omp` (`host-facts.mjs`) derive it, so one work item keeps one draft across every host. A draft the Plan wrote anywhere else reads as empty, and the review ledger then refuses the hand-off with `DRAFT_MISSING` instead of starting a Manager from nothing; the review carries no text of its own to fall back on. The Plan is TOLD the path: its system prompt is per agent and cannot carry a per-session value, so every `plan` receipt carries `draft` (the same `activation.draftPath(sessionID)` the port writes), declared in `tool-outputs.mjs`. Plan's `write` member is not path-gated by the adapter: §2.2 calls the single-file reach a responsibility boundary, not a runtime gate.",
    blocks: [],
  }),
]);

export const gap = (id) => GAPS.find((item) => item.id === id) ?? null;

export const gapText = (id) => {
  const found = gap(id);
  return found === null ? `unknown gap ${id}` : `${found.id}: ${found.question}`;
};

// The whole list, for a diagnostic export and for the gap table in AGENTS.md.
export const openGaps = () => GAPS;
