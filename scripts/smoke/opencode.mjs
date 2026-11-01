// Real-host smoke: OpenCode v2.
//
// One isolated config directory (`OPENCODE_CONFIG_DIR`) holding a copy of the
// operator's provider block and a `plugins` entry naming this repository's
// adapter DIRECTORY — the host resolves a directory to `index.js`, never to
// `plugin.mjs` (src/host-opencode/AGENTS.md). The operator's own config, auth
// and session store are never written.
//
// Three turns: two plain, the middle one asking for `js-manager`. The checks are
// the four facts a session must leave behind: host exit 0, no refusal code, a
// `js-*` tool result rendered as CUE, and `.Herdchestra/<session>.*` records.
//
// The tool results are read from the host's own log, which `--print-logs`
// writes to stderr, and from `.Herdchestra/<session>.calls.jsonl`, the adapter's
// call record. A run where the model skipped the tool is a failing check, not a
// retry.

import { mkdir, readdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

import {
  cleanupRoot,
  createTable,
  isCue,
  makeRoot,
  makeWorkspace,
  openCodeConfig,
  openCodeModel,
  parseArgs,
  readRecords,
  refusalsOf,
  spawnHost,
} from "./lib.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, "../..");
const PLUGIN_DIR = path.join(REPO, "src/host-opencode");

const FORBIDDEN = Object.freeze(["PREFIX_DRIFT", "CONTEXT_PROJECTION_FAILED", "REQUEST_GATE_UNWRITABLE"]);

const TURNS = Object.freeze([
  "Reply with the single word ok and stop.",
  [
    "Call the `js-manager` tool exactly once. Its only arguments are `source` (the program) and",
    "`scope` (the annotation):",
    '`{"source": "return { ok: true, value: 1 + 1 };", "scope": {"kind": "resetScope", "newScopeGoal": "smoke", "newScopeRetentionBudget": 5}}`.',
    "Then reply with the single word done and stop.",
  ].join(" "),
  "Reply with the single word ok and stop.",
]);

// The isolated config: the operator's providers (copied, never printed), the
// adapter directory as the only plugin, and no LSP or snapshot work.
const writeConfig = async (root) => {
  const configDir = path.join(root, "config");
  await mkdir(configDir, { recursive: true });
  const base = await openCodeConfig();
  const config = {
    $schema: "https://opencode.ai/config.json",
    provider: base.provider,
    permission: { "*": "allow" },
    lsp: false,
    snapshot: false,
    plugins: [PLUGIN_DIR],
  };
  await writeFile(path.join(configDir, "opencode.json"), JSON.stringify(config, null, 2));
  return configDir;
};

// The CUE detector is shared: `isCue` in ./lib.mjs, because all three hosts
// write the same document and the rule must not drift per script.

// `opencode run --format json` writes one JSON object per line: `{ type, part }`
// with `part.type` one of `step-start`, `text`, `tool`, `step-finish`. A `tool`
// part carries `tool` (the name) and `state.output` (what the model read).
// The host logs go to stderr with `--print-logs` and are searched for refusal
// codes only.
const toolResultsOf = (stream) => {
  const out = [];
  for (const line of stream.split("\n")) {
    if (!line.startsWith("{")) continue;
    let event;
    try {
      event = JSON.parse(line);
    } catch {
      continue;
    }
    const part = event.part ?? event;
    if (part?.type !== "tool") continue;
    const state = part.state ?? {};
    const output = state.output;
    const text = typeof output === "string" ? output : typeof output?.value === "string" ? output.value : typeof output?.text === "string" ? output.text : null;
    if (text === null) continue;
    out.push({ name: part.tool ?? part.toolName ?? "", text, status: state.status });
  }
  return out;
};

// One turn on one session. The first turn creates the session (`--session <id>`
// on a fresh id), every later turn continues it: the plugin's Pair Hint journal,
// Scope ledger and call record are per session, so three turns of one session
// exercise the projection the way a real conversation does — three separate
// sessions would each pay a cold start and never compare a second request.
const runTurn = async ({ workspace, configDir, sessionId, prompt, label }) => {
  const started = performance.now();
  const host = await spawnHost(
    "opencode",
    [
      "run",
      // `--standalone` runs a private server for this process. The background
      // service binds one fixed port (`opencode service get`), so three runs
      // sharing it collide ("port … already in use") and never reach a model.
      "--standalone",
      "--format",
      "json",
      "--print-logs",
      "--log-level",
      "info",
      "--model",
      openCodeModel(),
      "--session",
      sessionId,
      prompt,
    ],
    {
      cwd: workspace,
      env: {
        OPENCODE_CONFIG_DIR: configDir,
        // The plugin's own answer for "which workspace": on a live host the
        // plugin context names the SERVER's launch directory, so without this
        // the adapter writes its records into the repository it was launched
        // from. `HERDCHESTRA_PROJECT_ROOT` is the other adapters' variable.
        HERDCHESTRA_ROOT: workspace,
        HERDCHESTRA_PROJECT_ROOT: workspace,
        // Keep the host's own state out of the operator's tree.
        XDG_DATA_HOME: path.join(path.dirname(configDir), "data"),
        XDG_CACHE_HOME: path.join(path.dirname(configDir), "cache"),
        XDG_STATE_HOME: path.join(path.dirname(configDir), "state"),
      },
    },
  );
  const exit = await host.exited;
  return { exit, stdout: host.state.stdout, stderr: host.state.stderr, ms: performance.now() - started, label };
};

const sessionIdsOf = async (workspace) => {
  const names = await readdir(path.join(workspace, ".Herdchestra")).catch(() => []);
  const ids = new Set();
  for (const name of names) {
    const match = /^(.+?)\.[A-Za-z]+\.(jsonl|json)$/.exec(name);
    if (match !== null) ids.add(match[1]);
    else if (name.endsWith(".jsonl") || name.endsWith(".json")) ids.add(name.replace(/\.(jsonl|json)$/, ""));
  }
  return { names, ids: [...ids] };
};

const main = async () => {
  const args = parseArgs(process.argv.slice(2), { flags: ["keep", "verbose"] });
  const root = await makeRoot("opencode");
  const table = createTable();
  let failed = 0;
  const check = (phase, ok, note) => {
    if (!ok) failed += 1;
    table.record({ phase, ms: 0, ok, note });
  };

  try {
    const workspace = await makeWorkspace(root);
    const configDir = await writeConfig(root);

    // The session id is chosen here so the first turn creates exactly this one
    // and every later turn continues it.
    const sessionId = `ses_smoke${Date.now().toString(36)}`;
    const runs = [];
    for (const [index, prompt] of TURNS.entries()) {
      const run = await runTurn({ workspace, configDir, sessionId, prompt, label: `turn${index + 1}` });
      runs.push(run);
      table.record({ phase: run.label, ms: run.ms, ok: null, note: `exit ${run.exit.code}` });
      if (args.verbose) {
        console.log(`--- ${run.label} stdout`);
        console.log(run.stdout.slice(-3000));
        console.log(`--- ${run.label} stderr (plugin lines)`);
        console.log(run.stderr.split("\n").filter((line) => /plugin|tool|error/i.test(line)).slice(-15).join("\n"));
      }
    }

    const wall = runs.reduce((sum, run) => sum + run.ms, 0);
    table.record({ phase: "wall", ms: wall, ok: null, note: `${TURNS.length} turns` });

    const stream = runs.map((run) => run.stdout + "\n" + run.stderr).join("\n");
    const records = await readRecords(workspace);

    for (const run of runs) check(`exit ${run.label}`, run.exit.code === 0, run.exit.code === 0 ? undefined : `code ${run.exit.code}`);

    const inStream = FORBIDDEN.filter((code) => stream.includes(code));
    const refused = refusalsOf(records).map((record) => record.code ?? record.failure?.code ?? record.kind ?? "refused");
    const inRecords = FORBIDDEN.filter((code) => records.some((record) => JSON.stringify(record).includes(code)));
    check("no refusal codes", inStream.length === 0 && inRecords.length === 0 && refused.length === 0, [
      inStream.length > 0 ? `stream: ${inStream.join(",")}` : "",
      inRecords.length > 0 ? `records: ${inRecords.join(",")}` : "",
      refused.length > 0 ? `refused records: ${refused.join(",")}` : "",
    ].filter(Boolean).join(" "));

    // The last completed call is the one that answered: a host may stream an
    // earlier `running` receipt before the final one.
    const jsResults = runs.flatMap((run) => toolResultsOf(run.stdout)).filter((result) => result.name.startsWith("js-"));
    const completed = jsResults.filter((result) => result.status === undefined || result.status === "completed");
    const answer = completed.at(-1) ?? jsResults.at(-1);
    check("js tool called", jsResults.length > 0, jsResults.length === 0 ? "no js-* tool call in the stream" : `${jsResults.length} call(s)`);
    const text = answer?.text;
    check("tool output is CUE", isCue(text), isCue(text) ? undefined : `first 120 bytes: ${JSON.stringify((text ?? "").slice(0, 120))}`);

    const { names, ids } = await sessionIdsOf(workspace);
    const own = names.filter((name) => name.startsWith(sessionId));
    check("records exist", ids.length > 0 && own.length > 0, own.length === 0 ? `no .Herdchestra record for ${sessionId}` : `${own.length} file(s)`);

    if (args.verbose) console.log(JSON.stringify({ records: records.length, names }, null, 2));
  } catch (error) {
    failed += 1;
    table.record({ phase: "harness", ms: 0, ok: false, note: error.message });
  } finally {
    table.print();
    await cleanupRoot(root, args.keep);
  }
  process.exit(failed === 0 ? 0 : 1);
};

if (process.argv[1] === fileURLToPath(import.meta.url)) await main();
