// Start and stop a real Herdchestra server for one E2E test.  Every run gets
// its own temporary data directory, workspace, blob store and port; the
// user's default data directory is never opened.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(HERE, '../../..');
const PIXI_CLOJURE = path.join(REPO_ROOT, '.pixi/envs/default/bin/clojure');
const MAX_LOG_CHARS = 2 * 1024 * 1024;
// Heap is capped at 384m so the whole app JVM (heap + direct + metaspace +
// native) stays under the 512 MiB per-app RSS budget (plans/0.0.2-s2-perf.md).
const JVM_OPTS = ['-J-Xmx384m', '-J-XX:MaxDirectMemorySize=192m',
                  '-J-XX:MaxMetaspaceSize=384m', '-J-Xss512k',
                  '-J-XX:+ExitOnOutOfMemoryError',
                  // taoensso.nippy.io/read_typed is a 6084-byte method that
                  // intermittently SIGSEGVs inside C2-compiled code on both
                  // JDK 21 (rattler) and JDK 27 (hs_err_pid*.log, see
                  // HACK.md §16).  Excluding it keeps Datalevin/KV reads on
                  // the interpreter+C1 path.  Inner classes use `$` and the
                  // JDK 21 parser wants `class.method` form (no wildcards in
                  // the class part).  All three AFn entry points are excluded.
                  '-J-XX:CompileCommand=exclude,taoensso/nippy/io$read_typed.invokeStatic',
                  '-J-XX:CompileCommand=exclude,taoensso/nippy/io$read_typed.invoke',
                  '-J-XX:CompileCommand=exclude,taoensso/nippy/io$read_typed.invokePrim'];
// Diagnostic escape hatches for crash hunts: HC_E2E_KEEP_TEMP keeps the
// temp root after close, HC_E2E_JVM_OPTS appends raw -J options.
const EXTRA_JVM_OPTS = (process.env.HC_E2E_JVM_OPTS || '')
  .split(/\s+/).filter(Boolean);

function startOrphanWatchdog(child) {
  if (process.platform === 'win32' || !child.pid) return null;
  const parentPid = process.pid;
  const childPid = child.pid;
  const watchdogLog = process.env.HC_E2E_WATCHDOG_LOG
    || path.join(os.tmpdir(), 'hc-e2e-orphan-watchdog.log');
  const script = [
    `while kill -0 ${parentPid} 2>/dev/null; do sleep 2; done`,
    `echo "[orphan-watchdog] fired parent=${parentPid} child=${childPid} at=$(date -Is) cmd=$(ps -p ${childPid} -o command= 2>/dev/null | head -c 120)" >> ${JSON.stringify(watchdogLog)}`,
    `if ps -p ${childPid} -o command= 2>/dev/null | grep -q 'hc.server.app'; then`,
    `  kill -TERM ${childPid} 2>/dev/null`,
    `  sleep 5`,
    `  kill -KILL ${childPid} 2>/dev/null`,
    `fi`,
  ].join('\n');
  const watchdog = spawn('bash', ['-c', script], {
    stdio: 'ignore',
    detached: true,
  });
  watchdog.unref();
  return watchdog;
}

function stopOrphanWatchdog(watchdog) {
  if (!watchdog || watchdog.exitCode !== null) return;
  try {
    watchdog.kill('SIGTERM');
  } catch {
    // The watchdog is best effort; the app close path already ran.
  }
}

export function clojureCommand() {
  if (process.env.HC_CLOJURE) return process.env.HC_CLOJURE;
  if (fs.existsSync(PIXI_CLOJURE)) return PIXI_CLOJURE;
  return 'clojure';
}

function ednString(value) {
  return JSON.stringify(String(value));
}

function ednKeyword(value) {
  return `:${value}`;
}

// Serialize a plain JS value to EDN.  Maps become {:k v}, sequences [v],
// sets #{v}; strings are quoted; numbers/booleans/nil pass through;
// keywords use the :name form.  Only the shapes the config generators emit
// are supported — anything else throws instead of silently mis-encoding.
function ednValue(value) {
  if (value === null || value === undefined) return 'nil';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error(`ednValue: non-finite number ${value}`);
    return String(value);
  }
  // Strings that already carry a ':' prefix (produced by callers marking
  // EDN keywords, e.g. defaultTargetId) are emitted as keywords.
  if (typeof value === 'string' && value.startsWith(':')) return value;
  if (typeof value === 'string') return ednString(value);
  if (typeof value === 'bigint') return `${value}N`;
  if (value instanceof Date) return `#inst ${ednString(value.toISOString())}`;
  if (Array.isArray(value)) return `[${value.map(ednValue).join(' ')}]`;
  if (value instanceof Set) return `#{${[...value].map(ednValue).join(' ')}}`;
  if (typeof value === 'object') {
    const entries = Object.entries(value).map(([k, v]) => {
      // kebabKeys may already have produced ':keyword' strings; plain keys
      // become keywords.
      const key = k.startsWith(':') ? k : `:${k}`;
      return `${key} ${ednValue(v)}`;
    });
    return `{${entries.join(' ')}}`;
  }
  throw new Error(`ednValue: unsupported type ${typeof value}`);
}

function connectionEdn(connection) {
  const models = (connection.models || []).map((model) => {
    const fields = [
      `:id ${ednString(model.id)}`,
      `:model-name ${ednString(model.modelName || model.id)}`,
      `:display-name ${ednString(model.displayName || model.id)}`,
      `:max-output-tokens ${Number(model.maxOutputTokens || 2048)}`,
      `:enabled? ${model.enabled === false ? 'false' : 'true'}`,
    ];
    if (model.capabilities?.length) {
      fields.push(`:capabilities #{${model.capabilities.map(ednKeyword).join(' ')}}`);
    }
    return `{${fields.join(' ')}}`;
  });
  return `{:id ${ednString(connection.id)}
           :name ${ednString(connection.name)}
           :kind :openai-compatible
           :base-url ${ednString(connection.baseUrl)}
           :api-key ${ednString(connection.apiKey || '')}
           :timeout-ms ${Number(connection.timeoutMs || 60000)}
           :enabled? ${connection.enabled === false ? 'false' : 'true'}
           :models [${models.join(' ')}]}`;
}

function modelsEdn(models) {
  if (!models || models.connections.length === 0) return '{}';
  const connections = models.connections.map(connectionEdn).join(' ');
  const roles = Object.entries(models.roles || {}).map(([role, binding]) =>
    `:${role} {:connection ${ednString(binding.connection)} :model ${ednString(binding.model)}}`).join(' ');
  return `{:connections [${connections}] :roles {${roles}}}`;
}

// `:model-auth` section.  `options.modelAuth` is a plain map matching the
// EDN shape in hc.server.model-auth.provider/provider-keys, e.g.
//   { providers: { local: { id: 'local', name: 'Local OAuth',
//       authorizationEndpoint: 'http://127.0.0.1:PORT/authorize', ... } } }
// JS camelCase keys are converted to kebab-case EDN keywords.
function kebab(key) {
  return key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function kebabKeys(value) {
  if (Array.isArray(value)) return value.map(kebabKeys);
  if (value instanceof Set) return new Set([...value].map(kebabKeys));
  if (value && typeof value === 'object' && !(value instanceof Date)) {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [kebab(k), kebabKeys(v)]));
  }
  return value;
}

function modelAuthEdn(modelAuth) {
  if (!modelAuth) return 'nil';
  return ednValue(kebabKeys(modelAuth));
}

// `:backup` section: { targets: { webdav: {...}, s3: {...} },
//                      defaultTargetId: ':webdav' }.
function backupEdn(backup) {
  if (!backup) return 'nil';
  const normalized = { ...backup };
  if (normalized.defaultTargetId !== undefined && typeof normalized.defaultTargetId === 'string'
      && !normalized.defaultTargetId.startsWith(':')) {
    normalized.defaultTargetId = `:${normalized.defaultTargetId}`;
  }
  return ednValue(kebabKeys(normalized));
}

// `:search` section: { services: [...], defaultServiceId: 'id' }.
// Service entries are plain maps; credentials may carry :api-key inline or
// :api-key-env references.
function searchEdn(search) {
  if (!search) return 'nil';
  return ednValue(kebabKeys(search));
}

// `:clock` is a runtime hook in hc.server.app, not a parsed EDN value.  The
// generator still emits the section so a future consumer can pick it up;
// a numeric option records a fixed epoch-ms and `true` records "use real
// clock".  Absent means the app default.
function clockEdn(clock) {
  if (clock === undefined || clock === null) return 'nil';
  if (clock === true) return '{}';
  if (typeof clock === 'number' && Number.isFinite(clock)) {
    return `{:fixed-ms ${clock}}`;
  }
  throw new Error(`clock option must be true or a finite epoch-ms number, got ${JSON.stringify(clock)}`);
}

function writeWorkspace(root, files = {}) {
  for (const [relative, content] of Object.entries(files)) {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content);
  }
}

function waitForListening(child, timeoutMs = 120000) {
  const MAX_CAPTURE = 256 * 1024;
  const append = (current, text) => (current + text).slice(-MAX_CAPTURE);
  const failureDetail = (text) => {
    const lines = text.split('\n').map((line) => line.trim()).filter(Boolean);
    const syntaxAt = lines.findIndex((line) => /Syntax error reading source/.test(line));
    if (syntaxAt >= 0) return lines.slice(syntaxAt, syntaxAt + 2).join(' | ');
    return lines[0] || 'no stderr';
  };
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    let settled = false;
    const startedAt = Date.now();
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error(`server did not listen within ${timeoutMs} ms\nstdout:\n${stdout}\nstderr:\n${stderr}`));
    }, timeoutMs);
    const finish = (error, url) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve(url);
    };
    child.stdout.on('data', (chunk) => {
      stdout = append(stdout, chunk.toString());
      const match = stdout.match(/Herdchestra listening on (http:\/\/127\.0\.0\.1:\d+)/);
      if (match) finish(null, match[1]);
    });
    child.stderr.on('data', (chunk) => {
      const text = chunk.toString();
      stderr = append(stderr, text);
      // A Clojure reader error means the JVM will exit without ever
      // listening.  Fail immediately with the reader location instead of
      // waiting for the full listening timeout.
      if (!settled && /Syntax error reading source/.test(text)) {
        finish(new Error(`server syntax error before listening: ${failureDetail(stderr)}\nstdout:\n${stdout}\nstderr:\n${stderr}`));
      }
    });
    child.once('exit', (code, signal) => {
      const elapsed = Date.now() - startedAt;
      const early = elapsed < 5000 ? ` within ${elapsed} ms` : '';
      finish(new Error(
        `server exited before listening${early} (code=${code}, signal=${signal}): ${failureDetail(stderr)}\nstdout:\n${stdout}\nstderr:\n${stderr}`,
      ));
    });
    child.once('error', (error) => finish(error));
  });
}

async function health(url, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${url}/healthz`);
      const text = await response.text();
      if (response.ok && text.includes(':ok true')) return text;
      const errorsAt = text.indexOf(':component-errors');
      const detail = errorsAt >= 0
        ? text.slice(errorsAt, errorsAt + 3000)
        : text.slice(0, 4000);
      lastError = new Error(`healthz ${response.status}: ${detail}`);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw lastError || new Error('health check timed out');
}

export async function startApp(options = {}) {
  const tempRoot = options.root
    || (process.env.HC_E2E_KEEP_TEMP
        ? fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e-keep-'))
        : fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e-')));
  const dataDir = path.join(tempRoot, 'data');
  const workspaceRoot = path.join(tempRoot, 'workspace');
  const blobRoot = path.join(tempRoot, 'blobs');
  const worktreeRoot = path.join(tempRoot, 'worktrees');
  const artifactRoot = path.join(tempRoot, 'artifacts');
  const runtimeTmp = path.join(tempRoot, 'tmp');
  for (const dir of [dataDir, workspaceRoot, blobRoot, worktreeRoot, artifactRoot, runtimeTmp]) {
    fs.mkdirSync(dir, { recursive: true });
  }
  writeWorkspace(workspaceRoot, options.workspaceFiles);

  const configPath = path.join(tempRoot, 'config.edn');
  // A caller that restarts the same data directory with the same fixed
  // `port` keeps the browser origin (and therefore localStorage) stable;
  // the default 0 asks the OS for a fresh ephemeral port on every start.
  const config = `{:host "127.0.0.1"
 :port ${options.port ?? 0}
 :data-dir ${ednString(dataDir)}
 :workspace-root ${ednString(workspaceRoot)}
 :blob-root ${ednString(blobRoot)}
 :worktree-root ${ednString(worktreeRoot)}
 :artifact-root ${ednString(artifactRoot)}
 :static-root ${ednString(path.join(REPO_ROOT, 'resources/public'))}
 :log-level ${ednKeyword(process.env.HC_E2E_LOG_LEVEL || 'warn')}
 :model-stream? true
 :fleet {:preflight? false :gc-run-immediately? false}
${options.nudgeRoundBudget ? ` :nudge-round-budget ${Number(options.nudgeRoundBudget)}` : ''}
 :models ${modelsEdn(options.models)}
 :model-auth ${modelAuthEdn(options.modelAuth)}
 :backup ${backupEdn(options.backup)}
 :search ${searchEdn(options.search)}
 :clock ${clockEdn(options.clock)}}`;
  fs.writeFileSync(configPath, config);

  const logPath = options.evidenceDir
    ? path.join(options.evidenceDir, `server-${path.basename(tempRoot)}.log`)
    : null;
  if (logPath) fs.mkdirSync(path.dirname(logPath), { recursive: true });
  // HC_E2E_SIGTRACE wraps the launcher in a ptrace tracer that logs the
  // sender pid of every delivered signal; used to identify who SIGTERMs
  // the app JVM mid-test.  The traced child keeps the same pid, so
  // child.pid, the orphan watchdog and close() are unaffected.
  const sigtrace = process.env.HC_E2E_SIGTRACE;
  const launchArgv = [
    ...JVM_OPTS, ...EXTRA_JVM_OPTS,
    `-J-Djava.io.tmpdir=${runtimeTmp}`, '-M:server', '-m', 'hc.server.app', '--config', configPath];
  // `options.env` lets a spec pin the server's C locale (for example
  // LC_ALL=C so LMDB reports "No space left on device" rather than a
  // localized strerror string that the client's ENOSPC matcher cannot see).
  const childEnv = options.env ? { ...process.env, ...options.env } : process.env;
  const child = sigtrace
    ? spawn(sigtrace, [clojureCommand(), ...launchArgv],
            { cwd: REPO_ROOT, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'] })
    : spawn(clojureCommand(), launchArgv,
            { cwd: REPO_ROOT, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'] });
  let logs = '';
  const appendLog = (chunk) => {
    const text = chunk.toString();
    if (logs.length < MAX_LOG_CHARS) {
      logs += text.slice(0, MAX_LOG_CHARS - logs.length);
    }
    if (logPath) fs.appendFileSync(logPath, text);
  };
  child.stdout.on('data', appendLog);
  child.stderr.on('data', appendLog);
  // Record the exit status for the whole run, not only during startup:
  // a mid-test JVM death previously left no trace in the server log, which
  // made signal-vs-System/exit indistinguishable.
  child.on('exit', (code, signal) => {
    appendLog(Buffer.from(
      `[app-exit] pid=${child.pid} code=${code} signal=${signal} at=${new Date().toISOString()}\n`));
  });
  const watchdog = startOrphanWatchdog(child);

  try {
    const url = await waitForListening(child);
    await health(url, options.healthTimeoutMs ?? 60000);
    return {
      url,
      port: Number(new URL(url).port),
      pid: child.pid,
      tempRoot,
      dataDir,
      workspaceRoot,
      blobRoot,
      worktreeRoot,
      artifactRoot,
      configPath,
      logs: () => logs,
      async close() {
        stopOrphanWatchdog(watchdog);
        if (child.exitCode !== null || child.signalCode !== null) {
          if (!options.keepTemp) fs.rmSync(tempRoot, { recursive: true, force: true });
          return;
        }
        child.kill('SIGTERM');
        const exited = await Promise.race([
          new Promise((resolve) => child.once('exit', () => resolve(true))),
          new Promise((resolve) => setTimeout(() => resolve(false), 15000)),
        ]);
        if (!exited) child.kill('SIGKILL');
        if (!options.keepTemp && !process.env.HC_E2E_KEEP_TEMP) fs.rmSync(tempRoot, { recursive: true, force: true });
      },
    };
  } catch (error) {
    stopOrphanWatchdog(watchdog);
    child.kill('SIGKILL');
    if (!options.keepTemp && !process.env.HC_E2E_KEEP_TEMP) fs.rmSync(tempRoot, { recursive: true, force: true });
    throw error;
  }
}

// Run `--check` against an existing app root and return the process result.
//
// `--check` isolates itself into a temporary data directory unless the
// operator supplies one explicitly on the command line.  Passing
// `--data-dir`/`--workspace-root` here is deliberate: a recovery check must
// inspect the directory the previous run used, not a fresh one.  `--port 0`
// keeps the short-lived health listener away from the test's fixed port.
export function runAppCheck({ root, port = 0, timeoutMs = 300000 } = {}) {
  if (!root) throw new Error('runAppCheck: root is required');
  const configPath = path.join(root, 'config.edn');
  const dataDir = path.join(root, 'data');
  const workspaceRoot = path.join(root, 'workspace');
  const runtimeTmp = path.join(root, 'tmp');
  fs.mkdirSync(runtimeTmp, { recursive: true });
  const args = [
    ...JVM_OPTS, ...EXTRA_JVM_OPTS,
    `-J-Djava.io.tmpdir=${runtimeTmp}`,
    '-M:server', '-m', 'hc.server.app',
    '--config', configPath,
    '--check',
    '--port', String(port),
    '--data-dir', dataDir,
    '--workspace-root', workspaceRoot,
  ];
  const result = spawnSync(clojureCommand(), args, {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    timeout: timeoutMs,
    maxBuffer: 16 * 1024 * 1024,
  });
  return {
    exitCode: result.status,
    signal: result.signal ?? null,
    stdout: result.stdout || '',
    stderr: result.stderr || '',
    error: result.error ? String(result.error.message) : null,
    command: [clojureCommand(), ...args],
    configPath,
  };
}
