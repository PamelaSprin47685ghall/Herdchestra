// P-BENCH2: single-session 2000-activity benchmark.
//
// Phases:
//   1. seed   - run scripts/bench/seeder-2000.clj to write ONE session with
//               N mixed activity messages into <root>/data/db.
//   2. app    - start `clojure -M:server -m hc.server.app` with the
//               -Xmx384m / -XX:MaxDirectMemorySize=192m /
//               -XX:MaxMetaspaceSize=384m / -Xss512k caps and measure
//               launch -> first "Herdchestra listening" line.
//   3. mem    - poll /proc/<pid>/status for VmHWM/VmRSS.
//   4. dom    - Playwright opens the app, selects the session, measures:
//               conversation open time, rendered message DOM nodes,
//               scroll-up / scroll-down render window growth, and the
//               scroll-position restore after re-selecting the session.
//
// Usage:
//   node scripts/bench-2000-activity.mjs --root /tmp/hc-bench2/run \
//     --messages 2000 --msg-chars 400 --json out.json
//
// The script never kills processes it did not start; the app child is
// SIGTERM'd and SIGKILL'd only if it ignores SIGTERM.

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..');
const PIXI_CLOJURE = path.join(REPO_ROOT, '.pixi/envs/default/bin/clojure');
const JVM_OPTS = (process.env.HC_JVM_OPTS ||
  '-J-Xmx384m,-J-XX:MaxDirectMemorySize=192m,-J-XX:MaxMetaspaceSize=384m,-J-Xss512k,-J-XX:+ExitOnOutOfMemoryError')
  .split(',').map((s) => s.trim()).filter(Boolean);

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
  if (lastStatus != null) return { status: lastStatus, body: lastBody };
  throw last || new Error('healthz timeout');
}

function statusField(pid, field) {
  try {
    const t = fs.readFileSync(`/proc/${pid}/status`, 'utf8');
    const m = t.match(new RegExp(`^${field}:\\s+(\\d+)`, 'm'));
    return m ? Number(m[1]) : null;
  } catch { return null; }
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
    '-Sdeps', '{:paths ["src" "resources" "scripts" "test"]}',
    '-M:dev:server', '-m', 'bench.seeder-2000',
    '--data-dir', dataDir,
    '--sessions', String(sessions),
    '--messages', String(messages),
    '--msg-chars', String(msgChars),
  ];
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
  const root = arg('root', fs.mkdtempSync(path.join(os.tmpdir(), 'hc-bench2-')));
  const sessions = Number(arg('sessions', '1'));
  const messages = Number(arg('messages', '2000'));
  const msgChars = Number(arg('msg-chars', '400'));
  const skipSeed = process.argv.includes('--skip-seed');
  const keepTemp = process.argv.includes('--keep-temp');
  const jsonOut = arg('json', null);
  const evidenceDir = arg('evidence', path.join(REPO_ROOT, 'plans/evidence/p-bench2'));
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
    browser = await chromium.launch({
      executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome',
      headless: true,
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu',
             `--js-flags=--max-old-space-size=${process.env.HC_CHROME_HEAP_MB || 512}`,
             '--font-render-hinting=none'],
    });
    result.env.chrome = browser.version();
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

    // ---- list open + select the single session --------------------------
    const tList = Date.now();
    await page.goto(`${url}/#/sessions`, { waitUntil: 'load' });
    await page.locator('.wb-shell').first().waitFor({ state: 'visible', timeout: 30000 });
    const badge = page.locator('.wb-badge[aria-label^="连接状态"]');
    const syncDeadline = Date.now() + 300000;
    let badgeText = null;
    let synced = false;
    while (Date.now() < syncDeadline) {
      badgeText = await badge.first().textContent().catch(() => null);
      if (badgeText && badgeText.includes('已同步')) { synced = true; break; }
      await page.waitForTimeout(1000);
    }
    const syncMs = Date.now() - tList;

    await page.waitForTimeout(500);
    const sessionItems = await page.locator('.wb-session-item').count();
    const totalNodes = await page.evaluate(() => document.getElementsByTagName('*').length);
    const listOpenMs = Date.now() - tList;

    // Click the single session row.
    const row = page.locator('.wb-session-list .wb-list-button').first();
    const tSel = Date.now();
    await row.click();
    // Wait until the first message node appears or a generous timeout.
    await page.locator('[data-message-id]').first()
      .waitFor({ state: 'visible', timeout: 60000 }).catch(() => {});
    const openMs = Date.now() - tSel;
    await page.waitForTimeout(500);
    const messageNodes = await page.locator('[data-message-id]').count();
    const messageDomNodes = await page.evaluate(
      () => document.querySelectorAll('.hc-message-body, .hc-conversation-messages *').length);
    const totalAfterOpen = await page.evaluate(() => document.getElementsByTagName('*').length);
    const scroller = page.locator('.hc-conversation-messages').first();
    const scrollMetrics = await scroller.evaluate((el) => ({
      scrollHeight: el.scrollHeight, scrollTop: el.scrollTop, clientHeight: el.clientHeight,
    })).catch(() => null);

    // ---- scroll up: "显示更早消息" reveals older entries -----------------
    // The view keeps a 200-message tail window; the button expands it.
    const moreButton = page.locator('.hc-message-more button').first();
    const scrollProbe = { moreVisible: false, clicks: 0 };
    if (await moreButton.count()) {
      scrollProbe.moreVisible = true;
      // Click once to grow the window, then measure DOM growth.
      const before = await page.locator('[data-message-id]').count();
      await moreButton.click().catch(() => {});
      await page.waitForTimeout(400);
      scrollProbe.beforeCount = before;
      scrollProbe.afterCount = await page.locator('[data-message-id]').count();
      scrollProbe.clicks = 1;
    }

    // ---- scroll position restore ----------------------------------------
    // The stream reducer stores :scroll-top per session and the view writes
    // it back when the session is re-selected.  Simulate a real scroll by
    // dispatching a scroll event with a mid-list offset; the onScroll
    // handler records the value.  Then switch to another session (or
    // navigate away and back) and check the restored offset.
    const restoreProbe = {};
    const sc = page.locator('.hc-conversation-messages').first();
    const metrics0 = await sc.evaluate((el) => ({
      scrollHeight: el.scrollHeight, clientHeight: el.clientHeight,
    })).catch(() => null);
    // Scroll to a mid position and dispatch a scroll event so the React
    // onScroll handler records :scroll-top into :ui/stream.
    const target = metrics0 ? Math.floor(metrics0.scrollHeight * 0.4) : 2000;
    restoreProbe.targetScrollTop = await sc.evaluate((el, t) => {
      el.scrollTop = Math.min(t, el.scrollHeight - el.clientHeight);
      el.dispatchEvent(new Event('scroll', { bubbles: true }));
      return el.scrollTop;
    }, target).catch(() => null);
    restoreProbe.beforeScrollTop = restoreProbe.targetScrollTop;
    // Navigate away to #/board, then back to #/sessions and reselect.
    await page.evaluate(() => { window.location.hash = '#/board'; });
    await page.waitForTimeout(800);
    await page.evaluate(() => { window.location.hash = '#/sessions'; });
    await page.waitForTimeout(800);
    await row.click().catch(() => {});
    await page.locator('[data-message-id]').first()
      .waitFor({ state: 'visible', timeout: 30000 }).catch(() => {});
    await page.waitForTimeout(600);
    restoreProbe.afterScrollTop = await sc.evaluate((el) => el.scrollTop).catch(() => null);
    restoreProbe.metricsAfter = await sc.evaluate((el) => ({
      scrollHeight: el.scrollHeight, clientHeight: el.clientHeight,
    })).catch(() => null);
    restoreProbe.restored = (restoreProbe.afterScrollTop != null &&
                             Math.abs(restoreProbe.afterScrollTop -
                                      (restoreProbe.beforeScrollTop || 0)) < 50);

    result.dom = {
      listOpenMs, syncMs, sessionItems, totalNodes,
      openMs, messageNodes, messageDomNodes, totalAfterOpen,
      scrollMetrics, scrollProbe, restoreProbe,
      expectedSessions: sessions, expectedMessages: messages,
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

function finish(result, root, jsonOut, keepTemp, evidenceDir) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const jsonPath = jsonOut || path.join(evidenceDir, `bench-2000-${stamp}.json`);
  fs.mkdirSync(path.dirname(jsonPath), { recursive: true });
  fs.writeFileSync(jsonPath, JSON.stringify(result, null, 2));
  const peakHwmMiB = result.app?.peak?.hwmKb ? result.app.peak.hwmKb / 1024 : null;
  const peakRssMiB = result.app?.peak?.rssKb ? result.app.peak.rssKb / 1024 : null;
  console.log(JSON.stringify({
    json: jsonPath,
    seedMs: result.seed?.elapsedMs,
    listenMs: result.app?.listenMs,
    healthMs: result.app?.healthMs,
    peakHwmMiB: peakHwmMiB && +peakHwmMiB.toFixed(1),
    peakRssMiB: peakRssMiB && +peakRssMiB.toFixed(1),
    listOpenMs: result.dom?.listOpenMs,
    sessionItems: result.dom?.sessionItems,
    totalNodes: result.dom?.totalNodes,
    openMs: result.dom?.openMs,
    messageNodes: result.dom?.messageNodes,
    messageDomNodes: result.dom?.messageDomNodes,
    scrollMetrics: result.dom?.scrollMetrics,
    scrollProbe: result.dom?.scrollProbe,
    restoreProbe: result.dom?.restoreProbe,
  }, null, 2));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
