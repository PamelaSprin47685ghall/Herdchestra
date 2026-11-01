// Real-host smoke: Cline, through the `@cline/core` SDK the host itself loads.
//
// Cline's CLI is not installed here, and the Desktop binary starts a TUI. The
// SDK is resolved through `src/host-cline/host-modules.mjs` — the adapter's own
// ladder — with the known Linux install as the fallback. It is the faster route:
// no terminal, no hub handshake, one in-process host for the whole run.
//
// Isolation: `CLINE_DIR` points at a temporary root, so sessions, settings and
// logs land there instead of `~/.cline`. The provider block is copied from the
// operator's own `providers.json` — read to be handed to the host, never
// printed — and the workspace is a fresh directory under the same root.
//
// Three turns, each its own session (a second prompt on a live session is not
// reachable through this SDK): two plain, the middle one asking for
// `js-manager`. A fourth Plan session drives the `ask` park. The tool result is
// read from the adapter's `presented` journal and from the host's provider
// capture, which is what the model actually received.

import { cp, mkdir, mkdtemp, readdir, readFile, rm } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

import {
  cleanupRoot,
  clineProvider,
  clineSettingsDir,
  createTable,
  exists,
  FORBIDDEN_CODES,
  isCue,
  makeRoot,
  makeWorkspace,
  parseArgs,
  programRefusalsOf,
  readRecords,
  refusalsOf,
  until,
} from "./lib.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, "../..");
const ENTRY = path.join(REPO, "src/host-cline/index.js");
const CORE_FALLBACK = "/usr/lib/Cline/bin/desktop-backend/plugin-runtime/node_modules/@cline/core/dist/index.js";

// The SDK the adapter itself would resolve, not a path this script guesses. The
// ladder is `host-modules.mjs`'s; the known Linux install is the fallback for a
// machine where the ladder finds nothing (for example a source checkout that has
// not been built). `hostModuleSource()` names the step that answered.
const loadCore = async () => {
  let source = null;
  try {
    const hostModules = await import(pathToFileURL(path.join(REPO, "src/host-cline/host-modules.mjs")).href);
    const api = await hostModules.sessionHostApi();
    source = await hostModules.hostModuleSource();
    if (api?.kind === "clineCore" && typeof api.mod?.ClineCore?.create === "function") {
      return { ClineCore: api.mod.ClineCore, source };
    }
  } catch {
    // Fall through to the known layout; the failure note below reports both.
  }
  if (await exists(CORE_FALLBACK)) {
    const mod = await import(pathToFileURL(CORE_FALLBACK).href);
    if (typeof mod.ClineCore?.create === "function") return { ClineCore: mod.ClineCore, source: CORE_FALLBACK };
  }
  throw new Error(`@cline/core is not reachable (host-modules: ${source ?? "none"}; fallback: ${CORE_FALLBACK})`);
};

// The js-manager turn names the tool's REAL arguments: `code` and `scope`
// (`src/host-cline/adapter.mjs`, `CODE_SCHEMA`). It said `source` once, and the
// host answered "Tool call(s) missing required parameter `code`" — the tool was
// never called, and the two checks below then reported a failed tool rather
// than a wrong prompt.
const JS_PROGRAM = "return { ok: true, value: 1 + 1 };";

const TURNS = Object.freeze([
  "Reply with the single word ok and stop.",
  [
    "Call the `js-manager` tool exactly once. Its only arguments are `code` (the program) and",
    "`scope` (the annotation):",
    `\`${JSON.stringify({ code: JS_PROGRAM, scope: { kind: "resetScope", newScopeGoal: "smoke", newScopeRetentionBudget: 5 } })}\`.`,
    "Then reply with the single word done and stop.",
  ].join(" "),
  "Reply with the single word ok and stop.",
]);

// The role prompt the host's rule must put in every request of this session
// (根 §5, §7.2). The smoke config below says "You are a Manager."; these are the
// parts the ADAPTER contributes, so a capture that carries the config text and
// none of these proves the rule never ran.
const ROLE_RULE_MARKERS = Object.freeze(["You are in repo-aware mode", "<vibe-mode>"]);
// The Pair Hint pair: one synthetic `tool-call`/`tool-result` pair whose call id
// is `pair-hint:<epoch>:<digest>` and whose result carries the frozen text
// (§6.1). Either half alone is not the pair.
const PAIR_HINT_CALL = "pair-hint:";
const PAIR_HINT_RESULT = "<!-- pair-hint: resource=";

// Gap 5: a legal `ask` parks the run (根 §2.2 「中断（交还控制权）」). `this.ask`
// exists only in Plan's `S1`, so this is a `js-plan` call on a Plan session; the
// member parks, the receipt carries `interrupt: true`, and the run must end
// there — no second model step after the question was put to the operator.
const PARK_PROMPT = [
  "Call the `js-plan` tool exactly once with",
  `\`${JSON.stringify({ code: 'const a = this.ask("Which database should the service use?"); return a;', scope: { kind: "resetScope", newScopeGoal: "ask", newScopeRetentionBudget: 5 } })}\`.`,
  "Do not answer the question yourself.",
].join(" ");

// The model this run names: `provider/model`, split for the host's two fields.
const modelOf = () => {
  const raw = process.env.HC_SMOKE_CLINE_MODEL ?? "openai-compatible/lazy/task";
  const slash = raw.indexOf("/");
  return slash === -1 ? { providerId: clineProvider(), modelId: raw } : { providerId: raw.slice(0, slash), modelId: raw.slice(slash + 1) };
};

// The CUE detector is shared: `isCue` in ./lib.mjs, because all three hosts
// write the same document and the rule must not drift per script.

// Whether the text is a JSON document at all. The capture check counts a js-*
// result as CUE when it is non-empty and NOT JSON; the field-level rule lives
// in `isCue`.
const jsonValue = (text) => {
  try {
    JSON.parse(text);
    return true;
  } catch {
    return false;
  }
};

// The js-* tool result texts the PROVIDER received, in request order. This is
// the copy the model read (§6.3): the capture is written by the host after the
// hooks and after its own conversion. `unverified` is set (and the list left
// empty) when there is no capture to read at all, which is not a pass.
const sentCueResults = async (dir) => {
  const requests = await capturedRequests(dir);
  const out = [];
  for (const request of requests) {
    for (const message of request.messages) {
      for (const part of Array.isArray(message?.content) ? message.content : []) {
        if (part?.type !== "tool-result" && part?.type !== "tool_result") continue;
        const name = part?.toolName ?? part?.name;
        if (typeof name !== "string" || !name.startsWith("js-")) continue;
        const text = toolResultTextOf(part);
        if (text !== null) out.push(text);
      }
    }
  }
  if (out.length === 0 && requests.length === 0) out.unverified = true;
  return out;
};

// The display messages of a session, flattened to the parts that matter:
// the assistant's tool calls and the tool results, in order.
const partsOf = (messages) => {
  const out = [];
  for (const entry of messages ?? []) {
    const message = entry.message ?? entry;
    for (const part of message.content ?? []) {
      if (part?.type === "tool_use") out.push({ kind: "call", name: part.name ?? "", input: part.input });
      if (part?.type === "tool_result") out.push({ kind: "result", name: part.name ?? "", content: part.content });
    }
  }
  return out;
};

// The provider requests the host captured, read from its own files
// (`CLINE_CAPTURE_PROVIDER_REQUEST=full`, `CLINE_CAPTURE_DIR`). One file per
// request; `payload.system` and `payload.messages` are what the provider was
// sent. Nothing here prints the payload — only counts and markers.
const capturedRequests = async (dir) => {
  const names = (await readdir(dir).catch(() => [])).filter((name) => name.endsWith(".json")).sort();
  const out = [];
  for (const name of names) {
    try {
      const record = JSON.parse(await readFile(path.join(dir, name), "utf8"));
      const payload = record?.payload ?? null;
      if (payload === null || typeof payload !== "object") continue;
      out.push({
        name,
        timestamp: typeof record.timestamp === "string" ? record.timestamp : "",
        sessionId: typeof record.correlation?.sessionId === "string" ? record.correlation.sessionId : null,
        runId: typeof record.correlation?.runId === "string" ? record.correlation.runId : null,
        system: typeof payload.system === "string" ? payload.system : "",
        messages: Array.isArray(payload.messages) ? payload.messages : [],
        tools: Object.keys(payload.tools ?? {}),
        assistantText: (Array.isArray(payload.messages) ? payload.messages : [])
          .filter((message) => message?.role === "assistant")
          .flatMap((message) => (Array.isArray(message.content) ? message.content : []))
          .filter((part) => part?.type === "text")
          .map((part) => String(part.text ?? ""))
          .join(" "),
      });
    } catch {
      // A half-written capture is not a request; the next run has its own dir.
    }
  }
  return out;
};

// The text a captured provider `tool_result` block shows the model.
//
// The capture records the runtime's own part (measured: `{type:"tool-result",
// toolCallId, toolName, output}`), and `output` is the host's tagged value:
// `{type:"text", value}` for text, `{type:"json", value}` for structure. The
// host NESTS those — the provider conversion wraps the codec's persisted string
// — so the envelope is peeled until the value is the text itself.
const toolResultTextOf = (part) => {
  const raw = part?.output !== undefined ? part.output : part?.content;
  let current = raw;
  for (let depth = 0; depth < 8; depth += 1) {
    if (typeof current === "string") {
      // A string may still be one of our own envelopes, persisted as JSON.
      try {
        const parsed = JSON.parse(current);
        if (parsed !== null && typeof parsed === "object" && typeof parsed.type === "string") {
          current = parsed;
          continue;
        }
      } catch {
        // The tool's own text: this is what the model reads.
      }
      return current;
    }
    if (current === null || typeof current !== "object") return null;
    if (typeof current.value === "string") {
      // A wrapper whose value is ITSELF a persisted envelope is the double wrap
      // the host builds; the loop peels it, so the text is reached either way.
      current = current.value;
      continue;
    }
    if (typeof current.value === "object" && current.value !== null) {
      current = current.value;
      continue;
    }
    if (typeof current.text === "string") return current.text;
    return null;
  }
  return typeof current === "string" ? current : null;
};

// What one captured request carries of the role prompt and the Pair Hint pair.
// Counts, never text: a capture holds the whole provider payload.
const markersOf = (request) => {
  const system = request.system;
  const messages = JSON.stringify(request.messages);
  const calls = request.messages.flatMap((message) => (Array.isArray(message.content) ? message.content : []));
  // The Pair Hint pair, in the shape the capture holds: the runtime's own parts
  // (`tool-call` in an assistant message, `tool-result` in a `role:"tool"` one),
  // which is what `beforeModel` rewrote. The provider's own conversion runs
  // after the hooks, so a capture of it would show `tool_use`/`tool_result`; both
  // spellings are accepted so the check reads whichever the host recorded.
  const pairCall = calls.filter(
    (part) =>
      (part?.type === "tool-call" || part?.type === "tool_use") &&
      String(part.toolCallId ?? part.id ?? "").startsWith(PAIR_HINT_CALL),
  ).length;
  const pairResult = calls.filter(
    (part) =>
      (part?.type === "tool-result" || part?.type === "tool_result") &&
      String(toolResultTextOf(part) ?? "").includes(PAIR_HINT_RESULT),
  ).length;
  // A js-* tool result the model read as CUE: the adapter renders every
  // structured result as CUE (§6.3), so a non-JSON text is the proof that the
  // projection reached the provider.
  const jsResults = calls.filter((part) => {
    if (part?.type !== "tool-result" && part?.type !== "tool_result") return false;
    const name = part?.toolName ?? part?.name;
    return typeof name === "string" && name.startsWith("js-");
  });
  const cueResults = jsResults.filter((part) => {
    const text = toolResultTextOf(part);
    return typeof text === "string" && text.trim().length > 0 && !jsonValue(text.trim());
  });
  return {
    systemBytes: system.length,
    roleRule: ROLE_RULE_MARKERS.map((marker) => ({ marker, count: system.split(marker).length - 1 })),
    pairCall,
    pairResult,
    jsResults: jsResults.length,
    cueResults: cueResults.length,
    pairInMessages: messages.split(PAIR_HINT_RESULT).length - 1,
    // The hint's own record of the injection (`pair-hint` journal line), so the
    // check can tell "the host did not carry the pair" from "the hint never
    // injected one".
    pairInjected: (request.records ?? []).filter((record) => JSON.stringify(record).includes("pair-hint")).length,
  };
};

// The provider-request capture this run reads. `--capture` names the directory
// the run will use, so a kept root and its evidence sit together; without it
// the run gets its own temporary directory, which the run removes again unless
// `--keep` asks for the evidence.
const captureDir = async (root, args) => {
  const named = typeof args.capture === "string" ? args.capture : null;
  if (named !== null) return named;
  return await mkdtemp(path.join(tmpdir(), "hc-smoke-cline-capture-"));
};

// One plan session that asks the operator, started through the Plan ENTRY.
//
// A Plan is a session of its own with its own prompt and its own face
// (`startPlan`), so the park is driven the way an operator drives it: the entry
// starts the session, and the run's end is read from the host's own record.
// The proof is that the run ENDS after the park: a session that kept asking the
// model would add a second assistant turn, or a nudge asking it to carry on.
const runParkTurn = async ({ cline, providerId, modelId, dir, home, credentials }) => {
  const { startPlan } = await import(ENTRY);
  const started = performance.now();
  const plan = await startPlan({
    host: cline,
    text: PARK_PROMPT,
    root: dir,
    name: `park-${Date.now().toString(36)}`,
    providerId,
    modelId,
    credentials,
  });
  if (plan.ok !== true) return { ok: false, reason: plan.reason ?? "PLAN_NOT_STARTED", detail: plan.detail ?? null };
  // A parked run ENDS at the park: the host stops before the next model request
  // (`PARKED_ASK`, `guard.mjs`), so no assistant message ever follows the
  // `js-plan` result. Waiting for one is waiting for the very step the park
  // forbids, and it would cost the full settle timeout. The valid end here is
  // the `js-plan` call with its result and nothing after it.
  const parked = (messages) => {
    const list = Array.isArray(messages) ? messages : [];
    if (list.length === 0) return false;
    const parts = partsOf(list);
    // The model's `js-plan` call is there, and every call has its result: the
    // turn that parked has finished its tools.
    const call = parts.find((part) => part.kind === "call" && String(part.name).startsWith("js-"));
    if (call === undefined) return false;
    const calls = parts.filter((part) => part.kind === "call").length;
    if (parts.filter((part) => part.kind === "result").length !== calls) return false;
    // The turn that made the call is the LAST assistant turn: a later one is the
    // model step the park forbids, and the check below then fails on the count.
    const assistants = list.filter((entry) => (entry.message ?? entry).role === "assistant");
    const lastAssistant = assistants.at(-1);
    if (lastAssistant === undefined) return false;
    const lastParts = partsOf([lastAssistant]);
    return lastParts.some((part) => part.kind === "call" && String(part.name).startsWith("js-"));
  };
  const messages = await settle(cline, plan.sessionId, "park", { done: parked, timeout: 60_000 });
  const parts = partsOf(messages);
  const call = parts.find((part) => part.kind === "call" && String(part.name).startsWith("js-"));
  const record = JSON.parse(
    await readFile(path.join(home, "data", "sessions", plan.sessionId, `${plan.sessionId}.messages.json`), "utf8").catch(() => "null"),
  );
  const list = Array.isArray(record) ? record : (record?.messages ?? []);
  const assistants = list.filter((message) => message.role === "assistant");
  const text = assistants
    .flatMap((message) => (Array.isArray(message.content) ? message.content : []))
    .filter((part) => part?.type === "text")
    .map((part) => String(part.text ?? ""))
    .join(" ");
  return { ok: true, ms: performance.now() - started, sessionId: plan.sessionId, call: call?.name ?? null, assistantTurns: assistants.length, text, parts };
};

// One turn: one session, started with the turn's prompt.
//
// A second prompt on a live session is not reachable through this SDK: after
// `start` finishes its turn the run lifecycle releases the session, and both
// `ClineCore.send` (`runTurn`) and `start` with an explicit `config.sessionId`
// answer `session not found` / write a fresh two-message session (measured on
// @cline/core 0.2.0). `ClineCore.restore` is the checkpoint path and refuses a
// session with no checkpoint. So each turn is its own session, and the three
// together cost the same three provider turns a resumed session would.
const runTurn = async ({ cline, prompt, label, config, localRuntime }) => {
  const started = performance.now();
  const session = await cline.start({ prompt, config, localRuntime });
  const messages = await settle(cline, session.sessionId, label);
  return { messages, ms: performance.now() - started, label, sessionId: session.sessionId, issues: session.pluginIssues };
};

const settled = (messages) => {
  const list = Array.isArray(messages) ? messages : [];
  if (list.length === 0) return false;
  const parts = partsOf(list);
  const calls = parts.filter((part) => part.kind === "call").length;
  const results = parts.filter((part) => part.kind === "result").length;
  if (calls !== results) return false;
  const lastRole = (list.at(-1).message ?? list.at(-1)).role;
  return lastRole === "assistant";
};

// The text a tool result puts in front of the model, read from the adapter's
// `presented` record: the request it projected, one line per request. The
// host's own display messages keep the raw receipt (`{"name":…,"value":2}`),
// so they say what the tool answered, not what the model read; §6.3 is a rule
// about the latter, and `presented` is where the adapter writes it.
const presentedResults = async (workspace) => {
  const records = await readRecords(workspace);
  const out = [];
  for (const record of records) {
    if (record.kind !== "presented" || !Array.isArray(record.messages)) continue;
    for (const message of record.messages) {
      if (!Array.isArray(message?.content)) continue;
      for (const part of message.content) {
        // The worker transcript is `{session}-child-{id}.jsonl`; both carry the
        // same `presented` lines, so a name that starts with the session id is
        // this run's.
        if (part?.type !== "tool-result" || typeof part.toolName !== "string" || !part.toolName.startsWith("js-")) continue;
        // The `presented` record holds the copy the adapter built, which carries
        // the host's tagged value (`{type:"text", value}`), not the plain string
        // the provider finally sent.
        const raw = part.output;
        const text = typeof raw === "string" ? raw : typeof raw?.value === "string" ? raw.value : null;
        if (text !== null) out.push({ name: part.toolName, text });
      }
    }
  }
  return out;
};

// Waits until the newest message is the assistant's answer and every tool call
// has its result. Polling is the only signal this SDK offers for "turn done".
//
// `done` is the end this run expects, because not every run ends on an
// assistant message: a parked `ask` ends the run BEFORE the next model request
// (`PARKED_ASK`), so its last message is the tool result and no assistant turn
// follows it. Waiting for one there waits for the step the park forbids.
const settle = async (cline, sessionId, label, { done = settled, timeout = 180_000 } = {}) => {
  let messages = null;
  await until(
    async () => {
      messages = await cline.readDisplayMessages(sessionId);
      return done(messages);
    },
    { timeout, interval: 500, what: `${label} settling` },
  );
  return messages;
};

const sessionIdsOf = async (workspace) => {
  const names = await readdir(path.join(workspace, ".Herdchestra")).catch(() => []);
  const ids = new Set();
  for (const name of names) {
    const match = /^(.+?)\.(jsonl|json)$/.exec(name);
    if (match !== null) ids.add(match[1]);
    if (name.endsWith(".jsonl") || name.endsWith(".json")) ids.add(name.replace(/\.(jsonl|json)$/, ""));
  }
  return { names, ids: [...ids] };
};

const main = async () => {
  const args = parseArgs(process.argv.slice(2), { flags: ["keep", "verbose"], values: ["capture"] });
  const root = await makeRoot("cline");
  const table = createTable();
  let failed = 0;
  const check = (phase, ok, note) => {
    if (!ok) failed += 1;
    table.record({ phase, ms: 0, ok, note });
  };
  let cline = null;
  let capture = null;
  let captureIsTemporary = false;

  try {
    const workspace = await makeWorkspace(root);
    // The local backend inherits this process's cwd, and a session context that
    // carries no `workspaceInfo` resolves its root from it. Without the
    // explicit root, a worker's late roster snapshot lands in the operator's
    // own project (measured: `.Herdchestra/cline-session-1.workers.restored.json`
    // in the repository). The root is this run's workspace, so every session it
    // starts — and any write that outlives the turn — stays inside the
    // temporary tree the run removes.
    process.env.HERDCHESTRA_PROJECT_ROOT = workspace;
    // The host's own home for this run: settings copied read-only, sessions
    // written under the temporary root.
    const home = path.join(root, "cline-home");
    await mkdir(path.join(home, "data", "settings"), { recursive: true });
    const settings = clineSettingsDir();
    if (await exists(path.join(settings, "providers.json"))) {
      await cp(path.join(settings, "providers.json"), path.join(home, "data", "settings", "providers.json"));
    }
    process.env.CLINE_DIR = home;
    // The provider-request capture. The host reads both variables when the
    // process starts, so they are set before the SDK is imported, and the
    // directory is this run's own: a capture left by another run cannot pass
    // for this one's evidence.
    capture = await captureDir(root, args);
    captureIsTemporary = typeof args.capture !== "string";
    process.env.CLINE_CAPTURE_PROVIDER_REQUEST = "full";
    process.env.CLINE_CAPTURE_DIR = capture;

    // Resolved only after CLINE_DIR and the capture variables are set: the host
    // reads them when its module starts, and `loadCore` imports the SDK.
    const { ClineCore, source: coreSource } = await loadCore();
    cline = await ClineCore.create({ backendMode: "local" });
    const { providerId, modelId } = modelOf();

    // The request fields every turn's session starts with.
    const config = {
      providerId,
      modelId,
      cwd: workspace,
      workspaceRoot: workspace,
      // Builtin tools stay enabled: with `enableTools: false` the host builds a
      // request that carries no tools at all, so the model never sees the
      // plugin's face (measured; `toolPolicies` alone is not enough).
      enableTools: true,
      enableSpawnAgent: false,
      enableAgentTeams: false,
      systemPrompt: "You are a Manager. Use the js-manager tool when asked.",
      toolPolicies: { "*": { enabled: false }, "js-manager": { enabled: true }, fork: { enabled: true }, join: { enabled: true } },
    };
    const localRuntime = { pluginPaths: [ENTRY], configExtensions: ["rules", "skills", "plugins"] };

    const runs = [];
    for (const [index, prompt] of TURNS.entries()) {
      const run = await runTurn({ cline, prompt, label: `turn${index + 1}`, config, localRuntime });
      runs.push(run);
      if (args.verbose) console.log(JSON.stringify(partsOf(run.messages).slice(-3)).slice(0, 2000));
    }
    // The host answers `pluginIssues` only when it had something to report; a
    // clean load leaves the field out.
    const issues = runs.flatMap((run) => (Array.isArray(run.issues) ? run.issues : []));
    check("plugin loaded", issues.length === 0, issues.length === 0 ? undefined : JSON.stringify(issues));
    for (const run of runs) table.record({ phase: run.label, ms: run.ms, ok: null });

    const wall = runs.reduce((sum, run) => sum + run.ms, 0);
    table.record({ phase: "wall", ms: wall, ok: null, note: `${TURNS.length} turns` });

    // 1. The host answered every turn: a settled session with an assistant
    //    message after each prompt. A thrown turn already failed the harness.
    check("host answered", runs.every((run) => run.messages !== null), runs.map((run) => `${run.label}:${run.messages === null ? "none" : "ok"}`).join(" "));

    // 2. No refusal code in the records or in what the model was shown.
    const records = await readRecords(workspace);
    const refused = refusalsOf(records).map((record) => record.code ?? record.failure?.code ?? record.kind ?? "refused");
    const inRecords = FORBIDDEN_CODES.filter((code) => records.some((record) => JSON.stringify(record).includes(code)));
    const shown = runs.flatMap((run) => partsOf(run.messages)).map((part) => (typeof part.content === "string" ? part.content : JSON.stringify(part)));
    const inStream = FORBIDDEN_CODES.filter((code) => shown.some((text) => text.includes(code)));
    check("no refusal codes", inRecords.length === 0 && inStream.length === 0 && refused.length === 0, [
      inRecords.length > 0 ? `records: ${inRecords.join(",")}` : "",
      inStream.length > 0 ? `model saw: ${inStream.join(",")}` : "",
      refused.length > 0 ? `refused records: ${refused.join(",")}` : "",
    ].filter(Boolean).join(" "));

    // 3. A `js-*` tool was called, and the model read CUE.
    //
    //    The display view says the call happened (`tool_use` with the name), but
    //    its `tool_result` carries the raw receipt as a JSON string; the text the
    //    model actually read is what the adapter projected, and `presented`
    //    holds exactly that. Both are read, from their own source.
    //    Two sources, and only one of them is what the model read. The `presented`
    //    journal holds the copy the adapter handed to `beforeModel` (a tagged
    //    `{type:"text", value}`), while §6.3 is about what the LLM SAW: the host's
    //    provider capture. The capture is authoritative when it exists; without
    //    one the text is reported unverified, never passed.
    const presented = await presentedResults(workspace);
    check("js tool called", presented.length > 0, presented.length === 0 ? "no js-* tool result in any request" : `${presented.length} result(s)`);
    const presentedText = presented.at(-1)?.text;
    const seen = await sentCueResults(capture);
    const cueNote = (text) => (isCue(text) ? undefined : `first 120 bytes: ${JSON.stringify(String(text ?? "").slice(0, 120))}`);
    if (seen.length > 0) {
      const last = seen.at(-1);
      check("tool output is CUE", isCue(last), isCue(last) ? `${seen.length} js-* result(s) in the capture` : cueNote(last));
    } else if (seen.unverified) {
      // No capture to read: the journal copy is the adapter's intent, not proof.
      check("tool output is CUE", null, `unverified (no provider capture): journal holds ${JSON.stringify(String(presentedText ?? "").slice(0, 80))}`);
    } else {
      check("tool output is CUE", false, "no js-* tool result reached a provider request");
    }
    const programRefusals = [
      ...new Set([...presented.map((result) => result.text), ...seen].flatMap((text) => programRefusalsOf(text))),
    ];
    check(
      "js tool did not refuse",
      (presented.length > 0 || seen.length > 0) && programRefusals.length === 0,
      programRefusals.length > 0
        ? `program refusal: ${programRefusals.join(",")}`
        : presented.length === 0 && seen.length === 0
          ? "no js-* tool result to read"
          : undefined,
    );

    // 3b. The provider request itself: the host's capture of what it sent.
    //
    //     The checks above read the adapter's own record of the copy it built.
    //     This one reads the host's capture, so "the role prompt reached the
    //     provider" is a measurement of the request, not of the adapter's
    //     intent. Every request AFTER a session's cold start must carry the role
    //     rule and the Pair Hint pair; the host composes the first request of a
    //     session before the rule runs, so that one legitimately carries
    //     neither (HACK.md "Cline request rewrite").
    const captured = await capturedRequests(capture);
    const allMarked = captured.map((request) => markersOf(request));
    const sessionKeyOf = (request) => {
      if (request.sessionId !== null) return `session:${request.sessionId}`;
      if (request.runId !== null) return `run:${request.runId}`;
      // No correlation id: the first user message is the prompt that opened the
      // session, so equal prompts belong to the same cold-start group.
      const firstUser = request.messages.find((message) => message?.role === "user");
      const text = typeof firstUser?.content === "string" ? firstUser.content : JSON.stringify(firstUser?.content ?? null);
      return `messages:${text.slice(0, 200)}`;
    };
    const bySession = new Map();
    for (const [index, request] of captured.entries()) {
      const key = sessionKeyOf(request);
      const list = bySession.get(key) ?? [];
      list.push({ request, markers: allMarked[index] });
      bySession.set(key, list);
    }
    const projected = [];
    let coldStarts = 0;
    for (const list of bySession.values()) {
      list.sort((a, b) => (a.request.timestamp || a.request.name).localeCompare(b.request.timestamp || b.request.name));
      // The first request of a session is the cold-start one the host composes
      // before the rule runs; it is allowed to carry neither the rule nor the
      // pair. Every request after it is a projected copy and must carry both.
      const [, ...afterFirst] = list;
      coldStarts += 1;
      projected.push(...afterFirst);
    }
    const marked = projected.map((entry) => entry.markers);
    const ruleOk = marked.length > 0 && marked.every((m) => m.roleRule.every((entry) => entry.count >= 1));
    const pairOk = marked.length > 0 && marked.every((m) => m.pairCall >= 1 && m.pairResult >= 1);
    // The request copy's other two jobs, measured on the same captures: the
    // provider got the pair ONCE (a copy that carries it twice is the drift the
    // chain refuses), and a `js-*` result it received is CUE, not JSON (§6.3).
    const pairOnce = pairOk && marked.every((m) => m.pairCall === 1 && m.pairResult === 1);
    const cueResults = allMarked.reduce((sum, m) => sum + m.cueResults, 0);
    const jsResults = allMarked.reduce((sum, m) => sum + m.jsResults, 0);
    const projectedNote = (text) =>
      marked.length === 0 ? `no projected request after ${coldStarts} cold start(s)` : `${captured.length} request(s), ${coldStarts} cold start(s) skipped: ${text}`;
    check(
      "provider request has the role rule",
      ruleOk,
      captured.length === 0
        ? `no capture in ${capture}`
        : projectedNote(marked.map((m) => m.roleRule.map((entry) => `${entry.marker}=${entry.count}`).join(" ")).join(" | ")),
    );
    check(
      "provider request has the pair hint",
      pairOk,
      captured.length === 0
        ? `no capture in ${capture}`
        : projectedNote(marked.map((m) => `call=${m.pairCall} result=${m.pairResult} text=${m.pairInMessages}`).join(" | ")),
    );
    check("provider request carries the pair once", pairOnce, `${marked.filter((m) => m.pairCall === 1 && m.pairResult === 1).length}/${marked.length} projected request(s)`);
    check(
      "provider request has a CUE tool result",
      jsResults > 0 && cueResults > 0,
      jsResults === 0 ? "no js-* tool result in any capture" : `${cueResults}/${jsResults} js-* result(s) are CUE`,
    );

    // 4. Records for the session the host ran.
    const { names, ids } = await sessionIdsOf(workspace);
    const own = names.filter((name) => runs.some((run) => name.startsWith(run.sessionId)));
    check("records exist", ids.length > 0 && own.length > 0, own.length === 0 ? "no .Herdchestra record for any session" : `${own.length} file(s) for ${runs.length} session(s)`);

    // 5. Gap 5: a `this.ask` park ends its run with no further model step.
    //
    //    The park is a real `js-plan` call on a Plan session: the member parks,
    //    the receipt says `interrupt: true`, and `beforeModel` answers the
    //    host's stop control for the request that would otherwise follow. What
    //    the run must NOT do is ask the model again — a second assistant turn,
    //    or a nudge asking the model to carry on, would both show here.
    const planDir = path.join(root, "plan-workspace");
    await mkdir(planDir, { recursive: true });
    // The same stored credentials the three turns ran on: a Plan started with
    // the model alone reaches the provider and dies on a missing key.
    const { providerCredentials } = await import(path.join(REPO, "src/host-cline/host-modules.mjs"));
    const parked = await runParkTurn({ cline, providerId, modelId, dir: planDir, home, credentials: await providerCredentials({ providerId, modelId }) });
    table.record({ phase: "park", ms: parked.ms ?? 0, ok: parked.ok === true ? null : false, note: parked.ok === true ? (parked.call === null ? "no js-plan call" : `${parked.call} called`) : `${parked.reason} ${parked.detail ?? ""}`.trim() });
    check("park: the run ended after the ask", parked.ok === true && parked.assistantTurns === 1, parked.ok === true ? `${parked.assistantTurns} assistant turn(s)` : `the Plan session did not start: ${parked.reason}`);
    check(
      "park: no model step after the ask",
      parked.ok === true && parked.assistantTurns === 1 && !/nudge/i.test(parked.text),
      parked.ok !== true ? `the Plan session did not start: ${parked.reason}` : parked.text.length === 0 ? "no assistant text" : `${parked.text.length} chars of assistant text, no nudge`,
    );

    if (args.verbose) console.log(JSON.stringify({ records: records.length, names, capture, coreSource }, null, 2));
  } catch (error) {
    failed += 1;
    table.record({ phase: "harness", ms: 0, ok: false, note: error.message });
  } finally {
    // Every session of this run is released before the process exits; the
    // temporary root goes with it, so nothing is left behind either way.
    try {
      await cline?.dispose?.();
    } catch {
      /* the host is going away with the process */
    }
    table.print();
    // A kept root is evidence, not a credential store: the copied provider
    // settings are removed before the root is left behind.
    if (args.keep) {
      await rm(path.join(root, "cline-home", "data", "settings", "providers.json"), { force: true });
    }
    await cleanupRoot(root, args.keep);
    // The capture sits outside the root: without this it outlives the run that
    // wrote it, together with the whole provider payload it holds.
    if (capture !== null && captureIsTemporary && !args.keep) await rm(capture, { recursive: true, force: true });
  }
  process.exit(failed === 0 ? 0 : 1);
};

if (process.argv[1] === fileURLToPath(import.meta.url)) await main();
