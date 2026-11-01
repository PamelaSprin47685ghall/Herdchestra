// Real-host smoke: Oh My Pi.
//
// One isolated git workspace, one OMP process per turn, three short turns. The
// middle turn calls a `js-*` tool, so the run exercises the program surface, the
// Scope gate, the request gate and the CUE presentation — not just the model
// call.
//
// The checks are the five facts a session must leave behind:
//
//   1. the host exited 0;
//   2. no PREFIX_DRIFT / CONTEXT_PROJECTION_FAILED / REQUEST_GATE_UNWRITABLE in
//      the host's own stream or in the records under `.Herdchestra/`;
//   3. a `js-*` tool answered CUE, not JSON;
//   4. its output carries no program refusal code (SCOPE_REFUSED /
//      INVALID_ARGUMENT / SYNTAX_ERROR / PROGRAM_FAILED);
//   5. the adapter wrote `<session>.*` records for the session the host ran.
//
// A failing check is the result. The script prints every row, then exits
// nonzero; it never retries a turn to make a check pass.

import { execFile } from "node:child_process";
import { readdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { promisify } from "node:util";

import {
  cleanupRoot,
  createTable,
  FORBIDDEN_CODES,
  isCue,
  makeRoot,
  makeWorkspace,
  ompModel,
  parseArgs,
  programRefusalsOf,
  readRecords,
  refusalsOf,
  spawnHost,
} from "./lib.mjs";

const exec = promisify(execFile);

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, "../..");
const ENTRY = path.join(REPO, "src/host-omp/index.mjs");

// The three turns. Each is one host invocation. `js` names the turn that must
// call a program tool, and the prompt names the tool so a model that ignores it
// is a finding rather than a mystery.
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

// The CUE detector is shared: `isCue` in ./lib.mjs, because all three hosts
// write the same document and the rule must not drift per script.

// One host invocation. `--mode json` streams one JSON object per line; the
// tool results are the `message_end` events carrying `role: "toolResult"`.
const runTurn = async ({ workspace, root, prompt, label }) => {
  const started = performance.now();
  const host = await spawnHost(
    "omp",
    [
      "-p",
      "--mode",
      "json",
      "--no-extensions",
      "--no-lsp",
      "--no-pty",
      "--no-title",
      "--session-dir",
      path.join(root, "sessions"),
      "--model",
      ompModel(),
      "--extension",
      ENTRY,
      prompt,
    ],
    { cwd: workspace, env: { HERDCHESTRA_PROJECT_ROOT: workspace }, timeout: 600_000 },
  );
  const exit = await host.exited;
  return { exit, stdout: host.state.stdout, stderr: host.state.stderr, ms: performance.now() - started, label };
};

// Every JSON line of a stream, parsed; unparsable lines are kept as text so a
// check can search them instead of dropping them.
const lines = (text) =>
  text
    .split("\n")
    .filter((line) => line.trim().length > 0)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return { raw: line };
      }
    });

// The tool results of one turn: `{ name, text }` for each text block the host
// streamed back. OMP emits `tool_execution_end` with `toolName` and
// `result.content`, and a `message_end` whose message is a `toolResult` with the
// same `content`. Both are read, and the same call id is kept once: a synthetic
// `toolResult` (an aborted or skipped call) carries prose, never a receipt.
const toolResultsOf = (stream) => {
  const out = [];
  const seen = new Set();
  const keep = (id, name, content) => {
    if (typeof id === "string" && seen.has(id)) return;
    if (typeof id === "string") seen.add(id);
    for (const part of content ?? []) {
      if (part?.type === "text" && typeof part.text === "string") out.push({ name: name ?? "", text: part.text });
    }
  };
  for (const event of lines(stream)) {
    if (event?.type === "tool_execution_end") keep(event.toolCallId, event.toolName, event.result?.content);
    const message = event?.message ?? event?.assistantMessageEvent?.message;
    if (message?.role === "toolResult") keep(message.toolCallId, message.toolName, message.content);
  }
  return out;
};

// The session id the host used, read from the adapter's own bind record or from
// the record file names. Both are the adapter's answer; the host's stream does
// not carry it in print mode.
const sessionIdsOf = async (workspace) => {
  const dir = path.join(workspace, ".Herdchestra");
  const ids = new Set();
  for (const name of await readdir(dir).catch(() => [])) {
    const match = /^(.+?)\.(jsonl|json|scope\.jsonl|requests\.jsonl|session\.json|request\.json)$/.exec(name);
    if (match !== null) ids.add(match[1]);
  }
  return [...ids];
};

const main = async () => {
  const args = parseArgs(process.argv.slice(2), { flags: ["keep", "verbose"] });
  const root = await makeRoot("omp");
  const table = createTable();
  let failed = 0;
  const check = (phase, ok, note) => {
    if (!ok) failed += 1;
    table.record({ phase, ms: 0, ok, note });
  };

  try {
    const workspace = await makeWorkspace(root);
    await exec("git", ["init", "-q"], { cwd: workspace });
    await exec("git", ["add", "README.md"], { cwd: workspace });

    const runs = [];
    for (const [index, prompt] of TURNS.entries()) {
      const run = await runTurn({ workspace, root, prompt, label: `turn${index + 1}` });
      runs.push(run);
      table.record({ phase: run.label, ms: run.ms, ok: null, note: `exit ${run.exit.code}` });
      if (args.verbose) console.log(run.stdout.slice(-4000), run.stderr.slice(-2000));
    }

    const wall = runs.reduce((sum, run) => sum + run.ms, 0);
    table.record({ phase: "wall", ms: wall, ok: null, note: `${TURNS.length} turns` });

    const stream = runs.map((run) => run.stdout + "\n" + run.stderr).join("\n");
    const records = await readRecords(workspace);

    // 1. host exit 0 — every turn, because a mid-session crash still ends 0.
    for (const run of runs) check(`exit ${run.label}`, run.exit.code === 0, run.exit.code === 0 ? undefined : `code ${run.exit.code}`);

    // 2. no refusal code anywhere the adapter can write it.
    const inStream = FORBIDDEN_CODES.filter((code) => stream.includes(code));
    const refused = refusalsOf(records).map((record) => record.code ?? record.failure?.code ?? record.kind ?? "refused");
    const inRecords = FORBIDDEN_CODES.filter((code) => records.some((record) => JSON.stringify(record).includes(code)));
    check("no refusal codes", inStream.length === 0 && inRecords.length === 0 && refused.length === 0, [
      inStream.length > 0 ? `stream: ${inStream.join(",")}` : "",
      inRecords.length > 0 ? `records: ${inRecords.join(",")}` : "",
      refused.length > 0 ? `refused records: ${refused.join(",")}` : "",
    ].filter(Boolean).join(" "));

    // 3. a `js-*` tool answered CUE.
    // The last completed call is the one that answered: a host may stream an
    // earlier `running` receipt before the final one.
    const jsResults = runs.flatMap((run) => toolResultsOf(run.stdout)).filter((result) => result.name.startsWith("js-"));
    const completed = jsResults.filter((result) => result.status === undefined || result.status === "completed");
    const answer = completed.at(-1) ?? jsResults.at(-1);
    check("js tool called", jsResults.length > 0, jsResults.length === 0 ? "no js-* tool result in the stream" : `${jsResults.length} result(s)`);
    if (answer !== undefined) {
      const text = answer.text;
      check("tool output is CUE", isCue(text), isCue(text) ? undefined : `first 120 bytes: ${JSON.stringify(text.slice(0, 120))}`);
      const programRefusals = programRefusalsOf(text);
      check("js tool did not refuse", programRefusals.length === 0, programRefusals.length === 0 ? undefined : `program refusal: ${programRefusals.join(",")}`);
    } else {
      check("tool output is CUE", false, "no js-* tool result to read");
      check("js tool did not refuse", false, "no js-* tool result to read");
    }

    // 4. records for the session the host ran.
    const ids = await sessionIdsOf(workspace);
    const names = await readdir(path.join(workspace, ".Herdchestra")).catch(() => []);
    const perSession = ids.map((id) => `${id}: ${names.filter((name) => name.startsWith(id)).length}`);
    check("records exist", ids.length > 0 && names.length > 0, perSession.length === 0 ? "no .Herdchestra records" : perSession.join(" "));

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
