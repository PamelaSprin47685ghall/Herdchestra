// Shared parts of the real-host smoke harnesses (omp.mjs, opencode.mjs, cline.mjs).
//
// One host script per host, one library here. The library owns only what all
// three need to agree on: the isolated root, the provider configuration copied
// read-only from the operator's own files, the pass/fail table, argument
// parsing, and the one rule that matters for credentials — a secret is read to
// be handed to a child process and is never printed, logged or written into the
// repository.
//
// Each host script owns its own turn loop: OMP and OpenCode start a host process
// per turn, while Cline keeps one SDK host and starts one session per turn. The
// library shares only the pieces above, so a script can change its loop without
// changing the others.
//
// Why the root is under /tmp: a smoke run must never write into the operator's
// project. Every script creates a fresh `mkdtemp` root under `<tmp>`
// (`hc-smoke-<host>-<random>`) and removes it on exit unless `--keep` asks to
// keep it.

import { mkdir, mkdtemp, rm, readdir, readFile, writeFile, access } from "node:fs/promises";
import { tmpdir, homedir } from "node:os";
import path from "node:path";

// The model the smoke turns use. `lazy/task` is the cheapest model the
// authorized provider serves that still calls tools, which is what a host smoke
// must exercise; the provider name differs per host and each script maps it.
export const DEFAULT_MODEL = process.env.HC_SMOKE_MODEL ?? "lazy/task";

// ---------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------

// `--flag` booleans plus `--name value` options. Unknown options are an error
// rather than ignored: a typo must not silently run the whole set.
export const parseArgs = (argv, { flags = [], values = [] } = {}) => {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      out._.push(token);
      continue;
    }
    const [name, inline] = token.slice(2).split("=");
    if (flags.includes(name)) {
      out[name] = inline === undefined ? true : inline !== "false";
      continue;
    }
    if (!values.includes(name)) throw new Error(`unknown option --${name}`);
    const value = inline ?? argv[(i += 1)];
    if (value === undefined) throw new Error(`--${name} needs a value`);
    out[name] = value;
  }
  return out;
};

// ---------------------------------------------------------------------------
// The isolated root
// ---------------------------------------------------------------------------

export const makeRoot = async (host) => mkdtemp(path.join(tmpdir(), `hc-smoke-${host}-`));

export const cleanupRoot = async (root, keep) => {
  if (keep) {
    console.log(`kept ${root}`);
    return;
  }
  await rm(root, { recursive: true, force: true });
};

// A directory with one file. OMP's turn runner adds the git repository itself
// (`git init` + `git add`); the other hosts accept the plain directory.
export const makeWorkspace = async (root) => {
  const workspace = path.join(root, "workspace");
  await mkdir(workspace, { recursive: true });
  await writeFile(path.join(workspace, "README.md"), "# smoke\n");
  return workspace;
};

// ---------------------------------------------------------------------------
// Provider configuration, copied read-only
// ---------------------------------------------------------------------------

const readJson = async (file) => JSON.parse(await readFile(file, "utf8"));

export const exists = async (file) => {
  try {
    await access(file);
    return true;
  } catch {
    return false;
  }
};

// The OpenCode provider block from the operator's own config. The key is
// carried into the isolated config and never returned for printing.
export const openCodeConfig = async () => {
  const source = process.env.OPENCODE_CONFIG_FILE ?? path.join(homedir(), ".config/opencode/opencode.json");
  const config = await readJson(source);
  return {
    provider: config.provider ?? {},
    permission: { "*": "allow" },
    lsp: false,
    snapshot: false,
  };
};

// The Cline provider settings. Cline keeps providers in
// `<config>/data/settings/providers.json`; the isolated config gets a copy.
export const clineSettingsDir = () => path.join(homedir(), ".cline/data/settings");

// The model a Cline run names: `lazy/task` under the `openai-compatible`
// provider entry the operator configured.
export const clineProvider = () => process.env.HC_SMOKE_CLINE_PROVIDER ?? "openai-compatible";

// The OMP model id is `provider/model`; the operator's provider is `kw92.cyou`.
export const ompModel = () => process.env.HC_SMOKE_OMP_MODEL ?? `kw92.cyou/${DEFAULT_MODEL}`;

// The OpenCode model id is `provider/model`; the operator's provider is
// `ai.kw92.cyou`.
export const openCodeModel = () => process.env.HC_SMOKE_OPENCODE_MODEL ?? `ai.kw92.cyou/${DEFAULT_MODEL}`;

// ---------------------------------------------------------------------------
// Records
// ---------------------------------------------------------------------------

// Every `.jsonl` and `.json` under `<root>/.Herdchestra/`. A `.jsonl` file is
// parsed line by line; a `.json` file is one durable fact (a gate verdict, a
// ledger) or an array of them, so each parsed object becomes one record. A line
// or file that does not parse is returned as `{ unparsable: true, ... }` so a
// check can say so instead of throwing.
export const readRecords = async (workspace) => {
  const dir = path.join(workspace, ".Herdchestra");
  if (!(await exists(dir))) return [];
  const out = [];
  for (const name of await readdir(dir)) {
    const file = path.join(dir, name);
    if (name.endsWith(".jsonl")) {
      const text = await readFile(file, "utf8");
      for (const line of text.split("\n")) {
        if (line.trim().length === 0) continue;
        try {
          out.push({ file: name, ...JSON.parse(line) });
        } catch {
          out.push({ file: name, unparsable: true, line: line.slice(0, 200) });
        }
      }
      continue;
    }
    if (!name.endsWith(".json")) continue;
    let value;
    try {
      value = JSON.parse(await readFile(file, "utf8"));
    } catch {
      out.push({ file: name, unparsable: true, line: "" });
      continue;
    }
    for (const record of Array.isArray(value) ? value : [value]) {
      if (record !== null && typeof record === "object") out.push({ file: name, ...record });
      else out.push({ file: name, value: record });
    }
  }
  return out;
};

// The refusal codes that make a session unusable. `refused` is the Pair Hint
// journal's record; `gateHeld`/`failure` name the request gate. A drift record
// carries `failure.stage`/`failure.code`.
export const FORBIDDEN_CODES = Object.freeze(["PREFIX_DRIFT", "CONTEXT_PROJECTION_FAILED", "REQUEST_GATE_UNWRITABLE"]);

// The request gate's codes are the refusal codes every script searches for; one
// list keeps the three scripts from drifting apart.
export const REFUSAL_CODES = FORBIDDEN_CODES;

// The program's own refusals. A `js-*` tool that answers one of these did not
// run the program, so a CUE receipt alone is not a pass.
export const PROGRAM_REFUSAL_CODES = Object.freeze(["SCOPE_REFUSED", "INVALID_ARGUMENT", "SYNTAX_ERROR", "PROGRAM_FAILED"]);

export const programRefusalsOf = (text) =>
  typeof text === "string" ? PROGRAM_REFUSAL_CODES.filter((code) => text.includes(code)) : [];

export const refusalsOf = (records) =>
  records.filter((record) => {
    if (record.kind === "refused" || record.type === "refused") return true;
    const text = JSON.stringify(record);
    return REFUSAL_CODES.some((code) => text.includes(code));
  });

// CUE, not JSON: a structured value the model sees renders as `key: value`
// lines, so a tool result that is raw JSON is the failure §6.3 forbids.
//
// The three hosts write the same document and open it differently: Cline and
// OpenCode as bare fields (`name: x`), OMP as a receipt wrapped in braces
// (`{\n  ok: true\n}`), which starts with `{` and is still not JSON. So the
// test is on the body, not the first line:
//
//   - an OBJECT or ARRAY that `JSON.parse` accepts is rejected;
//   - a struct's surrounding braces are removed, then a field line
//     (`key: value`, any indentation) or a heredoc (`#"""`, `"""`) is required;
//   - prose that happens to hold a colon on one line still passes this test —
//     a detector cannot tell a sentence from a field, and the caller reports the
//     first bytes when it fails, which is where a prose answer shows.
//
// `JSON.parse` also accepts a bare scalar (`"x"`, `1`); that is not the
// question, because only an object or an array is the raw JSON the rule names.
export const isCue = (text) => {
  if (typeof text !== "string") return false;
  const body = text.trim();
  if (body.length === 0) return false;
  const structured = body.startsWith("{") || body.startsWith("[");
  if (structured) {
    try {
      JSON.parse(body);
      return false;
    } catch {
      // Braces that do not parse are a CUE struct; read its fields below.
    }
  }
  const fields = structured && body.endsWith("}") ? body.slice(1, -1) : body;
  if (/^\s*[A-Za-z_$][A-Za-z0-9_$]*:[ \t]*\S/m.test(fields)) return true;
  const opens = (line) => line.trimStart().startsWith('#"""') || line.trimStart().startsWith('"""');
  return fields.split("\n").some(opens);
};

// ---------------------------------------------------------------------------
// The table
// ---------------------------------------------------------------------------

// Rows are `{ phase, ms, ok, note }`. The table prints after every scenario, so
// a failing run still shows where the time went.
export const createTable = () => {
  const rows = [];
  const record = (row) => {
    rows.push(row);
    return row;
  };
  const print = () => {
    const width = rows.reduce((max, row) => Math.max(max, row.phase.length), 5);
    console.log("");
    console.log(`${"phase".padEnd(width)}  ${"ms".padStart(8)}  result`);
    console.log(`${"-".repeat(width)}  ${"-".repeat(8)}  ${"-".repeat(6)}`);
    for (const row of rows) {
      const mark = row.ok === false ? "FAIL" : row.ok === true ? "pass" : "info";
      const note = row.note === undefined ? "" : `  ${row.note}`;
      console.log(`${row.phase.padEnd(width)}  ${String(Math.round(row.ms)).padStart(8)}  ${mark}${note}`);
    }
    const failed = rows.filter((row) => row.ok === false).length;
    const total = rows.filter((row) => typeof row.ms === "number").reduce((sum, row) => sum + row.ms, 0);
    console.log(`${"-".repeat(width)}  ${"-".repeat(8)}  ${"-".repeat(6)}`);
    console.log(`${"total".padEnd(width)}  ${String(Math.round(total)).padStart(8)}  ${failed === 0 ? "pass" : `${failed} failed`}`);
    console.log("");
  };
  return { record, print, rows };
};

// Times one async step and records it. A thrown step is recorded as a failure
// and rethrown only when `fatal` says the run cannot continue.
export const step = async (table, phase, run, { fatal = true, note = undefined } = {}) => {
  const started = performance.now();
  try {
    const value = await run();
    table.record({ phase, ms: performance.now() - started, ok: true, note });
    return value;
  } catch (error) {
    table.record({ phase, ms: performance.now() - started, ok: false, note: note ?? error.message });
    if (fatal) throw error;
    return undefined;
  }
};

// ---------------------------------------------------------------------------
// Child processes
// ---------------------------------------------------------------------------

// A child whose stdout/stderr are collected. The child is killed with its group
// so a host that spawns its own workers does not outlive the run. `timeout`
// bounds one turn: a hung host is sent SIGTERM (then SIGKILL after a grace
// period) instead of leaving an orphan process group behind.
export const spawnHost = async (command, args, { cwd, env = {}, onLine = null, timeout = 300_000 }) => {
  const { spawn } = await import("node:child_process");
  const child = spawn(command, args, {
    cwd,
    env: { ...process.env, ...env },
    stdio: ["ignore", "pipe", "pipe"],
    detached: true,
  });
  const state = { stdout: "", stderr: "", lines: [], exit: null, timedOut: false };
  const feed = (stream, key) => {
    let rest = "";
    stream.setEncoding("utf8");
    stream.on("data", (chunk) => {
      state[key] += chunk;
      if (onLine === null) return;
      rest += chunk;
      const parts = rest.split("\n");
      rest = parts.pop();
      for (const line of parts) {
        state.lines.push(line);
        onLine(line);
      }
    });
  };
  feed(child.stdout, "stdout");
  feed(child.stderr, "stderr");
  const exited = new Promise((resolve) => {
    child.on("exit", (code, signal) => {
      state.exit = { code, signal };
      clearTimeout(timer);
      resolve(state.exit);
    });
  });
  const kill = () => {
    if (state.exit !== null) return;
    try {
      process.kill(-child.pid, "SIGTERM");
    } catch {
      /* already gone */
    }
  };
  let timer = null;
  if (typeof timeout === "number" && timeout > 0) {
    timer = setTimeout(() => {
      state.timedOut = true;
      kill();
      // A host that ignores SIGTERM would otherwise keep the run waiting for an
      // exit that never comes; the second signal is the backstop.
      const force = setTimeout(() => {
        if (state.exit !== null) return;
        try {
          process.kill(-child.pid, "SIGKILL");
        } catch {
          /* already gone */
        }
      }, 5_000);
      force.unref?.();
    }, timeout);
    timer.unref?.();
  }
  return { child, state, exited, kill };
};

// Waits until `predicate()` is true, checking after every tick of the event
// loop and at most every `interval` ms. No fixed sleep: the wait ends when the
// condition holds, not when a timer expires.
export const until = async (predicate, { timeout = 120_000, interval = 25, what = "condition" } = {}) => {
  const deadline = performance.now() + timeout;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (performance.now() > deadline) throw new Error(`${what} did not hold within ${timeout} ms`);
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
};

// The first free TCP port, asked of the kernel rather than guessed from a range.
export const freePort = async () => {
  const { createServer } = await import("node:net");
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
};
