// Start and stop a real Herdchestra server for one E2E test.  Every run gets
// its own temporary data directory, workspace, blob store and port; the
// user's default data directory is never opened.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(HERE, '../../..');
const PIXI_CLOJURE = path.join(REPO_ROOT, '.pixi/envs/default/bin/clojure');
const MAX_LOG_CHARS = 2 * 1024 * 1024;
const JVM_OPTS = ['-J-Xmx768m', '-J-XX:MaxDirectMemorySize=512m',
                  '-J-XX:+ExitOnOutOfMemoryError'];

function startOrphanWatchdog(child) {
  if (process.platform === 'win32' || !child.pid) return null;
  const parentPid = process.pid;
  const childPid = child.pid;
  const script = [
    `while kill -0 ${parentPid} 2>/dev/null; do sleep 2; done`,
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
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    let settled = false;
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
      stderr = append(stderr, chunk.toString());
    });
    child.once('exit', (code, signal) => {
      finish(new Error(`server exited before listening (code=${code}, signal=${signal})\nstdout:\n${stdout}\nstderr:\n${stderr}`));
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
  const tempRoot = options.root || fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e-'));
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
  const config = `{:host "127.0.0.1"
 :port ${options.port ?? 0}
 :data-dir ${ednString(dataDir)}
 :workspace-root ${ednString(workspaceRoot)}
 :blob-root ${ednString(blobRoot)}
 :worktree-root ${ednString(worktreeRoot)}
 :artifact-root ${ednString(artifactRoot)}
 :static-root ${ednString(path.join(REPO_ROOT, 'resources/public'))}
 :log-level :warn
 :model-stream? true
 :fleet {:preflight? false :gc-run-immediately? false}
 :models ${modelsEdn(options.models)}}`;
  fs.writeFileSync(configPath, config);

  const logPath = options.evidenceDir
    ? path.join(options.evidenceDir, `server-${path.basename(tempRoot)}.log`)
    : null;
  if (logPath) fs.mkdirSync(path.dirname(logPath), { recursive: true });
  const child = spawn(clojureCommand(),
    [...JVM_OPTS, `-J-Djava.io.tmpdir=${runtimeTmp}`, '-M:server', '-m', 'hc.server.app', '--config', configPath],
    {
      cwd: REPO_ROOT,
      env: process.env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
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
  const watchdog = startOrphanWatchdog(child);

  try {
    const url = await waitForListening(child);
    await health(url, options.healthTimeoutMs ?? 60000);
    return {
      url,
      port: Number(new URL(url).port),
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
        if (!options.keepTemp) fs.rmSync(tempRoot, { recursive: true, force: true });
      },
    };
  } catch (error) {
    stopOrphanWatchdog(watchdog);
    child.kill('SIGKILL');
    if (!options.keepTemp) fs.rmSync(tempRoot, { recursive: true, force: true });
    throw error;
  }
}
