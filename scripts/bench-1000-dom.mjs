// P-BENCH1 1000-session benchmark.
//
// Phases:
//   1. seed   - run scripts/bench/seeder.clj to write N sessions into
//               <root>/data/db (Datalevin store under :data-dir/db).
//   2. app    - start `clojure -M:server -m hc.server.app` with the
//               -Xmx384m / -XX:MaxDirectMemorySize=192m /
//               -XX:MaxMetaspaceSize=384m / -Xss512k caps and measure
//               launch -> first healthz response.
//   3. mem    - poll /proc/<pid>/status for VmHWM/VmRSS while the app
//               runs and after the DOM probe.
//   4. dom    - Playwright opens /#/sessions, waits for sync, counts
//               .wb-session-item nodes and total DOM nodes, then selects
//               one session and counts rendered message nodes.
//
// Usage:
//   node scripts/bench-1000-dom.mjs --root /tmp/hc-bench1/run \
//     --sessions 1000 --messages 20 --msg-chars 400 --json out.json
//
// The script never kills processes it did not start; the app child is
// SIGTERM'd and SIGKILL'd only if it ignores SIGTERM.

import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..');
const BUNDLE_PATH = 'resources/public/js/main.js';
const PIXI_CLOJURE = path.join(REPO_ROOT, '.pixi/envs/default/bin/clojure');
const JVM_OPTS = (process.env.HC_JVM_OPTS ||
  '-J-Xmx384m,-J-XX:MaxDirectMemorySize=192m,-J-XX:MaxMetaspaceSize=384m,-J-Xss512k,-J-XX:+ExitOnOutOfMemoryError')
  .split(',').map((s) => s.trim()).filter(Boolean);
const SEED_PATHS = ['src', 'resources', 'scripts', 'test'];

function bundleInfo() {
  const file = path.join(REPO_ROOT, BUNDLE_PATH);
  const bytes = fs.readFileSync(file);
  return {
    path: BUNDLE_PATH,
    bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex'),
    mtime: fs.statSync(file).mtime.toISOString(),
  };
}

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
}

function clojureCommand() {
  if (process.env.HC_CLOJURE) return process.env.HC_CLOJURE;
  if (fs.existsSync(PIXI_CLOJURE)) return PIXI_CLOJURE;
  return 'clojure';
}

function ednString(v) { return JSON.stringify(String(v)); }

function writeWorkspace(root, files = {}) {
  for (const [rel, content] of Object.entries(files)) {
    const target = path.join(root, rel);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content);
  }
}

function waitForListening(child, timeoutMs =
  Number(process.env.HC_LISTEN_TIMEOUT_MS || 300000)) {
  return new Promise((resolve, reject) => {
    let stdout = '', stderr = '', settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error(`server did not listen within ${timeoutMs} ms\nstdout:\n${stdout}\nstderr:\n${stderr}`));
    }, timeoutMs);
    const finish = (err, url) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      err ? reject(err) : resolve(url);
    };
    child.stdout.on('data', (c) => {
      stdout += c.toString();
      const m = stdout.match(/Herdchestra listening on (http:\/\/127\.0\.0\.1:\d+)/);
      if (m) finish(null, m[1]);
    });
    child.stderr.on('data', (c) => { stderr += c.toString(); });
    child.once('exit', (code, signal) => finish(
      new Error(`server exited before listening code=${code} signal=${signal}\nstdout:\n${stdout}\nstderr:\n${stderr}`)));
    child.once('error', finish);
  });
}

async function healthOk(url, timeoutMs = 120000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  let lastStatus = null;
  let lastBody = null;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${url}/healthz`);
      const t = await r.text();
      if (r.ok && t.includes(':ok true')) return { status: r.status, body: t };
      lastStatus = r.status;
      lastBody = t;
      if (process.env.HC_HEALTH_DUMP) {
        fs.writeFileSync(process.env.HC_HEALTH_DUMP, t);
      }
      last = new Error(`healthz ${r.status}`);
    } catch (e) { last = e; }
    await new Promise((r) => setTimeout(r, 300));
  }
  // Any HTTP response means the server is up; return the last one so the
  // caller can record the health state without aborting the benchmark.
  if (lastStatus != null) return { status: lastStatus, body: lastBody };
  throw last || new Error('healthz timeout');
}

function psRssKb(pid) {
  try {
    const out = execFileSync('ps', ['-o', 'rss=', '-p', String(pid)], { encoding: 'utf8' });
    const n = Number(out.trim());
    return Number.isFinite(n) && n > 0 ? n : null;
  } catch { return null; }
}

function statusField(pid, field) {
  try {
    const t = fs.readFileSync(`/proc/${pid}/status`, 'utf8');
    const m = t.match(new RegExp(`^${field}:\\s+(\\d+)`, 'm'));
    return m ? Number(m[1]) : null;
  } catch {
    return psRssKb(pid);
  }
}

function startMemSampler(pid, intervalMs = 250) {
  const samples = [];
  const timer = setInterval(() => {
    const hwm = statusField(pid, 'VmHWM');
    const rss = statusField(pid, 'VmRSS');
    if (hwm != null || rss != null) samples.push({ t: Date.now(), hwm, rss });
  }, intervalMs);
  return {
    samples,
    stop() { clearInterval(timer); },
    peak() {
      let hwm = 0, rss = 0;
      for (const s of samples) { hwm = Math.max(hwm, s.hwm || 0); rss = Math.max(rss, s.rss || 0); }
      return { hwmKb: hwm, rssKb: rss };
    },
  };
}

async function seed(root, sessions, messages, msgChars) {
  const dataDir = path.join(root, 'data');
  const seedArgs = [
    '-Sdeps', `{${JSON.stringify(`:paths ["src" "resources" "scripts" "test"]`).slice(1, -1)}}`,
    '-M:dev:server', '-m', 'bench.seeder',
    '--data-dir', dataDir,
    '--sessions', String(sessions),
    '--messages', String(messages),
    '--msg-chars', String(msgChars),
  ];
  // -Sdeps takes a literal EDN map; build it directly.
  seedArgs[1] = '{:paths ["src" "resources" "scripts" "test"]}';
  return new Promise((resolve, reject) => {
    const child = spawn(clojureCommand(), seedArgs, { cwd: REPO_ROOT, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '', err = '';
    child.stdout.on('data', (c) => { out += c; });
    child.stderr.on('data', (c) => { err += c; });
    child.once('exit', (code) => {
      if (code === 0) resolve({ out, err });
      else reject(new Error(`seeder exited ${code}\nstdout:\n${out}\nstderr:\n${err.slice(-2000)}`));
    });
  });
}

async function main() {
  const root = arg('root', fs.mkdtempSync(path.join(os.tmpdir(), 'hc-bench1-')));
  const sessions = Number(arg('sessions', '1000'));
  const messages = Number(arg('messages', '20'));
  const msgChars = Number(arg('msg-chars', '400'));
  const skipSeed = process.argv.includes('--skip-seed');
  const keepTemp = process.argv.includes('--keep-temp');
  const jsonOut = arg('json', null);
  const evidenceDir = arg('evidence', path.join(REPO_ROOT, 'plans/evidence/p-bench1'));
  fs.mkdirSync(evidenceDir, { recursive: true });

  const result = {
    env: {
      node: process.version,
      platform: process.platform,
      arch: process.arch,
      cpus: os.cpus().length,
      totalMemBytes: os.totalmem(),
      freeMemBytes: os.freemem(),
      chrome: null,
    },
    config: { root, sessions, messages, msgChars,
              jvmOpts: JVM_OPTS, staticRoot: 'resources/public' },
    bundle: bundleInfo(),
    seed: null, app: null, dom: null,
    startedAt: new Date().toISOString(),
  };

  // ---- phase 1: seed -----------------------------------------------------
  const t0 = Date.now();
  if (skipSeed) {
    result.seed = { skipped: true };
  } else {
    const seeded = await seed(root, sessions, messages, msgChars);
    result.seed = { elapsedMs: Date.now() - t0, log: seeded.out.trim().split('\n').pop() };
  }

  // ---- phase 2: app start -------------------------------------------------
  const dataDir = path.join(root, 'data');
  const workspaceRoot = path.join(root, 'workspace');
  const blobRoot = path.join(root, 'blobs');
  const worktreeRoot = path.join(root, 'worktrees');
  const artifactRoot = path.join(root, 'artifacts');
  const runtimeTmp = path.join(root, 'tmp');
  for (const d of [dataDir, workspaceRoot, blobRoot, worktreeRoot, artifactRoot, runtimeTmp]) {
    fs.mkdirSync(d, { recursive: true });
  }
  writeWorkspace(workspaceRoot, {});

  const configPath = path.join(root, 'config.edn');
  const config = `{:host "127.0.0.1"
 :port 0
 :data-dir ${ednString(dataDir)}
 :workspace-root ${ednString(workspaceRoot)}
 :blob-root ${ednString(blobRoot)}
 :worktree-root ${ednString(worktreeRoot)}
 :artifact-root ${ednString(artifactRoot)}
 :static-root ${ednString(path.join(REPO_ROOT, 'resources/public'))}
 :log-level :warn
 :model-stream? true
 :fleet {:preflight? false :gc-run-immediately? false}
 :models {}
 :model-auth nil
 :backup nil
 :search nil
 :clock {}}`;
  fs.writeFileSync(configPath, config);

  const logPath = path.join(evidenceDir, `server-${path.basename(root)}.log`);
  const child = spawn(clojureCommand(),
    [...JVM_OPTS, `-J-Djava.io.tmpdir=${runtimeTmp}`,
     '-M:server', '-m', 'hc.server.app', '--config', configPath],
    { cwd: REPO_ROOT, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
  let appLog = '';
  const appendLog = (c) => { appLog += c.toString(); if (appLog.length > 4 * 1024 * 1024) appLog = appLog.slice(-2 * 1024 * 1024); };
  child.stdout.on('data', appendLog);
  child.stderr.on('data', appendLog);
  fs.writeFileSync(logPath, '');
  child.stdout.on('data', (c) => fs.appendFileSync(logPath, c));
  child.stderr.on('data', (c) => fs.appendFileSync(logPath, c));

  const sampler = startMemSampler(child.pid, 250);
  const appStart = Date.now();
  let url;
  try {
    url = await waitForListening(child);
    const listenMs = Date.now() - appStart;
    // Probe /healthz once for the record but do not gate on :ok; with 1000
    // sessions the snapshot can be slow and :sessions {:count nil} is a
    // known symptom under investigation.
    let health = null;
    try { health = await healthOk(url, 120000); } catch (e) { health = { error: String(e) }; }
    const healthMs = Date.now() - appStart;
    result.app = { url, pid: child.pid, listenMs, healthMs,
                   healthStatus: health?.status,
                   healthOkFlag: health?.body?.includes(':ok true') ?? null,
                   healthError: health?.error };
  } catch (e) {
    sampler.stop();
    child.kill('SIGKILL');
    result.app = { error: String(e), logTail: appLog.slice(-4000) };
    finish(result, root, jsonOut, keepTemp, evidenceDir);
    return;
  }

  // ---- phase 3+4: DOM probe ----------------------------------------------
  let browser;
  try {
    const chromeExe = process.env.CHROME_PATH || process.env.HC_CHROME || [
      '/usr/bin/google-chrome',
      '/usr/bin/google-chrome-stable',
      '/usr/bin/chromium',
      '/usr/bin/chromium-browser',
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Chromium.app/Contents/MacOS/Chromium',
    ].find((p) => fs.existsSync(p));
    browser = await chromium.launch({
      ...(chromeExe ? { executablePath: chromeExe } : {}),
      headless: true,
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu',
             `--js-flags=--max-old-space-size=${process.env.HC_CHROME_HEAP_MB || 512}`,
             '--font-render-hinting=none'],
    });
    result.env.chrome = browser.version();
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const tList = Date.now();
    await page.goto(`${url}/#/sessions`, { waitUntil: 'load' });
    await page.locator('.wb-shell').first().waitFor({ state: 'visible', timeout: 30000 });
    // The baseline for 1000 sessions is large; allow up to 5 minutes for
    // the sync badge.  If it never reaches 已同步 we still record the DOM
    // state so the report can show the failure mode.
    const badge = page.locator('.wb-badge[aria-label^="连接状态"]');
    // Wait up to 5 minutes for the baseline; the 1000-session snapshot is
    // large and the badge only flips to 已同步 after the state object lands.
    const syncDeadline = Date.now() + 300000;
    let badgeText = null;
    let synced = false;
    while (Date.now() < syncDeadline) {
      badgeText = await badge.first().textContent().catch(() => null);
      if (badgeText && badgeText.includes('已同步')) { synced = true; break; }
      await page.waitForTimeout(1000);
    }
    const syncMs = Date.now() - tList;

    // Give the list one extra beat for rows, then count.
    await page.waitForTimeout(500);
    const sessionItems = await page.locator('.wb-session-item').count();
    const totalNodes = await page.evaluate(() => document.getElementsByTagName('*').length);
    const listOpenMs = Date.now() - tList;

    // Select the first session and count rendered messages.
    const row = page.locator('.wb-session-list .wb-list-button').first();
    const tSel = Date.now();
    await row.click();
    await page.locator('.hc-conversation-messages, .hc-message-body').first()
      .waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
    const selectMs = Date.now() - tSel;
    const messageNodes = await page.locator('[data-message-id]').count();
    const messageDomNodes = await page.evaluate(() => document.querySelectorAll('.hc-message-body, .hc-conversation-messages *').length);
    const totalAfter = await page.evaluate(() => document.getElementsByTagName('*').length);

    result.dom = {
      listOpenMs, syncMs, sessionItems, totalNodes,
      selectMs, messageNodes, messageDomNodes, totalAfter,
      expectedSessions: sessions, expectedMessagesPerSession: messages,
      synced, badgeText,
    };
  } catch (e) {
    result.dom = { error: String(e) };
  } finally {
    if (browser) await browser.close().catch(() => {});
  }

  sampler.stop();
  const peak = sampler.peak();
  result.app.peak = peak;
  result.app.logTail = appLog.slice(-2000);

  // Graceful stop: SIGTERM, then SIGKILL only our own child if needed.
  if (child.exitCode === null && child.signalCode === null) {
    child.kill('SIGTERM');
    const exited = await Promise.race([
      new Promise((r) => child.once('exit', () => r(true))),
      new Promise((r) => setTimeout(() => r(false), 20000)),
    ]);
    if (!exited) child.kill('SIGKILL');
  }
  finish(result, root, jsonOut, keepTemp, evidenceDir);
}

function verdictFor(result) {
  const reasons = [];
  if (result.app?.error) reasons.push('app-not-ready');
  if (!result.dom || result.dom.error) reasons.push('dom-not-ready');
  else {
    if (result.dom.synced !== true) reasons.push('baseline-not-synced');
    if (!Number.isInteger(result.dom.sessionItems)) reasons.push('session-list-missing');
    if (!Number.isInteger(result.dom.totalNodes)) reasons.push('dom-count-missing');
  }
  if (result.bundleStable === false) reasons.push('bundle-changed');
  const readiness = reasons.filter((reason) => reason !== 'bundle-changed');
  return {
    verdict: readiness.length
      ? 'FAIL (app-not-ready)'
      : (reasons.length ? 'FAIL (bundle-changed)' : 'PASS'),
    reasons,
  };
}

function finish(result, root, jsonOut, keepTemp, evidenceDir) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const jsonPath = jsonOut || path.join(evidenceDir, `bench-1000-${stamp}.json`);
  result.bundleAfter = bundleInfo();
  result.bundleStable = (result.bundleAfter.sha256 === result.bundle.sha256
                         && result.bundleAfter.bytes === result.bundle.bytes);
  const judged = verdictFor(result);
  result.verdict = judged.verdict;
  result.verdictReasons = judged.reasons;
  fs.mkdirSync(path.dirname(jsonPath), { recursive: true });
  fs.writeFileSync(jsonPath, JSON.stringify(result, null, 2));
  const peakHwmMiB = result.app?.peak?.hwmKb ? result.app.peak.hwmKb / 1024 : null;
  const peakRssMiB = result.app?.peak?.rssKb ? result.app.peak.rssKb / 1024 : null;
  console.log(JSON.stringify({
    json: jsonPath,
    verdict: result.verdict,
    verdictReasons: result.verdictReasons,
    bundleSha256: result.bundle?.sha256,
    bundleBytes: result.bundle?.bytes,
    bundleStable: result.bundleStable,
    bundleAfterSha256: result.bundleAfter?.sha256,
    seedMs: result.seed?.elapsedMs,
    listenMs: result.app?.listenMs,
    healthMs: result.app?.healthMs,
    peakHwmMiB: peakHwmMiB && +peakHwmMiB.toFixed(1),
    peakRssMiB: peakRssMiB && +peakRssMiB.toFixed(1),
    listOpenMs: result.dom?.listOpenMs,
    sessionItems: result.dom?.sessionItems,
    totalNodes: result.dom?.totalNodes,
    messageNodes: result.dom?.messageNodes,
    messageDomNodes: result.dom?.messageDomNodes,
    selectMs: result.dom?.selectMs,
  }, null, 2));
  process.exitCode = result.verdict === 'PASS' ? 0 : 1;
  if (!keepTemp) {
    // Keep the store for inspection by default; pass --keep-temp to keep the
    // whole root.  Removing /tmp data is the operator's call.
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
