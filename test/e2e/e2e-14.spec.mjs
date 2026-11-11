// E2E 14: managed browser observation, operator takeover and tab close.
//
// The test drives the real workbench UI against a real Herdchestra server
// whose `:browser :endpoint` points at a second, isolated headless Chrome
// launched by this spec (the same shape as test/js/verify_browser_ui.cjs).
// A local HTTP server hosts a three-step form page; the managed tab is
// reached through Playwright's CDP connection so real trusted input can be
// typed into the controlled page.
//
// Covered acceptance points:
//   * managed-browser pane mounts and the permission flow works;
//   * multi-step form page cards appear in order;
//   * trusted operator input pauses the agent; resume re-reads the DOM;
//   * a sensitive field is never filled by the agent;
//   * closing the tab keeps the session and its page cards.
import { test, chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startApp, REPO_ROOT } from './support/app.mjs';
import {
  clientBundleInfo, goto, newSession, waitForShell, waitForSync, writeJson,
} from './support/ui.mjs';
import { sourceTreeSha256 } from './support/evidence.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE_DIR = path.join(REPO_ROOT, 'plans/evidence/e2e-14');
const CHROME = process.env.CHROME_PATH || '/usr/bin/google-chrome';
const FREEZE = {
  bundleSha256: '45f11529a22da07d23e665c412ad6b1611758d63e28bd464727efa32e174fd30',
  treeSha256: '76cf2fd01952d06d46932eea1406f849452ed6e0285b2458f7794d1214a42f7b',
};
const BROWSER_TEXT_TIMEOUT_MS = 90000;
const BROWSER_TEXT_POLL_MS = 1500;
const diagnostics = {};

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

function waitHttp(url, timeoutMs = 30000) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      const req = http.get(url, (res) => {
        res.resume();
        resolve(res.statusCode);
      });
      req.on('error', () => {
        if (Date.now() - started > timeoutMs) reject(new Error(`timeout ${url}`));
        else setTimeout(tick, 250);
      });
      req.setTimeout(1000, () => req.destroy());
    };
    tick();
  });
}

// Three-step form.  Each step is a distinct document state reached by a real
// button click; step 3 holds a sensitive card-number field inside a form so
// the server-side sensitive-form refusal is exercised.
const PAGE_HTML = `<!doctype html><html><head><meta charset="utf-8">
<title>E2E14 Form</title></head><body>
<div id="app">
  <div id="echo" aria-live="polite"></div>
  <section id="step1">
    <h1>E2E14 Step 1 名称</h1>
    <label for="name">姓名</label>
    <input id="name" name="name" type="text" autocomplete="off"
           oninput="document.getElementById('echo').textContent=this.value">
    <button id="to-step2" type="button">下一步</button>
  </section>
  <section id="step2" hidden>
    <h1>E2E14 Step 2 联系方式</h1>
    <label for="email">邮箱</label>
    <input id="email" name="email" type="email" autocomplete="off"
           oninput="document.getElementById('echo').textContent=this.value">
    <button id="to-step3" type="button">下一步</button>
  </section>
  <section id="step3" hidden>
    <h1>E2E14 Step 3 付款</h1>
    <form id="pay" onsubmit="event.preventDefault();document.getElementById('done').hidden=false;">
      <label for="pw">密码</label>
      <input id="pw" name="password" type="password" autocomplete="off">
      <label for="note">备注</label>
      <input id="note" name="note" type="text" autocomplete="off"
             oninput="document.getElementById('echo').textContent=this.value">
      <button id="pay-submit" type="submit">提交付款</button>
    </form>
    <p id="done" hidden>E2E14 已提交</p>
  </section>
</div>
<script>
document.getElementById('to-step2').addEventListener('click', () => {
  document.getElementById('step1').hidden = true;
  document.getElementById('step2').hidden = false;
});
document.getElementById('to-step3').addEventListener('click', () => {
  document.getElementById('step2').hidden = true;
  document.getElementById('step3').hidden = false;
});
</script>
</body></html>`;

async function shot(page, name) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  return file;
}

async function browserText(page) {
  return page.evaluate(() => {
    const el = document.querySelector('.hc-browser');
    return el ? (el.innerText || el.textContent || '') : '';
  }).catch(() => '');
}

async function waitBrowserText(page, needle, timeout = BROWSER_TEXT_TIMEOUT_MS) {
  const started = Date.now();
  let lastText = '';
  while (Date.now() - started < timeout) {
    lastText = await browserText(page);
    diagnostics.lastBrowserText = lastText.slice(0, 200);
    if (lastText.includes(needle)) return lastText;
    const remaining = timeout - (Date.now() - started);
    if (remaining <= 0) break;
    await page.waitForTimeout(Math.min(BROWSER_TEXT_POLL_MS, remaining));
  }
  throw new Error(
    `managed browser text did not contain ${JSON.stringify(needle)} within ${timeout}ms; `
    + `lastBrowserText=${JSON.stringify(diagnostics.lastBrowserText || '')}`,
  );
}

async function clickById(page, id) {
  const el = page.locator(`#${id}`);
  await el.waitFor({ state: 'visible', timeout: 15000 });
  await page.waitForFunction(
    (id) => {
      const node = document.getElementById(id);
      return node && !node.disabled;
    },
    id, { timeout: 30000 });
  await el.evaluate((node) => node.click());
}

async function selectNode(page, needle) {
  // A background snapshot refresh can re-render the node list between the
  // row lookup and the click; retry the select a few times before failing.
  let lastError = null;
  for (let attempt = 0; attempt < 4; attempt++) {
    const row = page.locator('.hc-browser-node')
      .filter({ hasText: needle })
      .first();
    await row.waitFor({ state: 'visible', timeout: 15000 });
    await row.getByRole('button', { name: '选择' }).evaluate((el) => el.click());
    try {
      await page.waitForSelector('.hc-browser-node-selected', { timeout: 4000 });
      return;
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError || new Error('node selection did not render');
}

async function observe(page) {
  const btn = page.locator('#hc-browser-observe');
  await btn.waitFor({ state: 'visible', timeout: 15000 });
  await page.waitForFunction(
    () => {
      const el = document.querySelector('#hc-browser-observe');
      return el && !el.disabled;
    }, null, { timeout: 30000 });
  await btn.evaluate((el) => el.click());
}

test('@e2e E2E 14 managed browser observe, takeover and tab close', async ({ page, browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const checks = {};
  const evidence = {
    test: 'E2E 14',
    freeze: FREEZE,
    // Captured at test runtime, never copied from the FREEZE constants.
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    diagnostics,
    checks,
    screenshots: [],
    startedAt: new Date().toISOString(),
  };
  const writeEvidence = () => {
    try {
      writeJson(EVIDENCE_DIR, 'e2e-14', evidence);
    } catch { /* best effort */ }
  };
  const mark = (name) => { checks[name] = true; writeEvidence(); };
  page.setDefaultTimeout(30000);

  // --- fixtures ---------------------------------------------------------
  const pagePort = await freePort();
  const chromePort = await freePort();
  const pageServer = http.createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(PAGE_HTML);
  });
  await new Promise((resolve) => pageServer.listen(pagePort, '127.0.0.1', resolve));
  const pageUrl = `http://127.0.0.1:${pagePort}/`;

  const chromeData = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e14-chrome-'));
  const chrome = spawn(CHROME, [
    '--headless=new',
    `--remote-debugging-port=${chromePort}`,
    '--remote-allow-origins=*',
    `--user-data-dir=${chromeData}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    'about:blank',
  ], { stdio: ['ignore', 'ignore', 'ignore'] });
  await waitHttp(`http://127.0.0.1:${chromePort}/json/version`);

  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e14-app-'));
  const prevEndpoint = process.env.HC_BROWSER_ENDPOINT;
  process.env.HC_BROWSER_ENDPOINT = `http://127.0.0.1:${chromePort}`;
  let app;
  let managed;
  try {
    app = await startApp({ root: tempRoot, port: 0, evidenceDir: EVIDENCE_DIR });
    evidence.appUrl = app.url;
    evidence.managedChrome = { port: chromePort, endpoint: process.env.HC_BROWSER_ENDPOINT };

    // A session must exist so `:ui/browser` carries a session-id and the
    // browser service for that session is created lazily with its Owner.
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await waitForShell(page);
    // `:session/ensure` creates the Owner (and its browser service)
    // lazily; give the receipt a moment before browser commands fly.
    await page.waitForTimeout(1500);

    // --- pane mount + permission ----------------------------------------
    await goto(page, app.url, '/materials');
    await page.locator('.wb-content').waitFor({ state: 'visible', timeout: 30000 });
    await page.locator('.wb-content .wb-tab', { hasText: '浏览器' })
      .first().click();
    await page.locator('.hc-browser').waitFor({ state: 'visible', timeout: 30000 });
    checks.paneMounted = true;
    evidence.screenshots.push(await shot(page, '01-pane-mounted'));

    const enableButton = page.locator('#hc-browser-enable');
    if (await enableButton.count()) {
      await page.locator('#hc-browser-site').fill('127.0.0.1');
      await page.locator('.hc-browser-field')
        .filter({ hasText: '允许范围' })
        .locator('select')
        .selectOption({ label: '指定站点' });
      await enableButton.evaluate((el) => el.click());
      await page.waitForFunction(
        () => !document.querySelector('#hc-browser-enable'),
        null, { timeout: 30000 });
    }
    await waitBrowserText(page, '已启用', 90000);
    checks.permissionEnabled = true;
    evidence.screenshots.push(await shot(page, '02-permission-enabled'));

    // --- open + observe step 1 ------------------------------------------
    await page.getByLabel('浏览器地址').fill(pageUrl);
    await clickById(page, 'hc-browser-open');
    // The managed tab is a page inside the second Chrome's CDP context.
    const cdp = await chromium.connectOverCDP(`http://127.0.0.1:${chromePort}`);
    try {
      const ctx = cdp.contexts()[0] || await cdp.newContext();
      managed = ctx.pages().find((p) => p.url().startsWith(pageUrl));
      if (!managed) {
        // The tab may still be navigating; poll briefly.
        for (let i = 0; i < 40 && !managed; i++) {
          await page.waitForTimeout(500);
          managed = ctx.pages().find((p) => p.url().startsWith(pageUrl));
        }
      }
      assert(managed, 'managed tab not found in CDP context');
      managed.setDefaultTimeout(30000);
      await managed.waitForSelector('#step1:not([hidden])', { timeout: 30000 });
      mark('tabOpened');

      // The page always contains the step-3 password field (hidden but in
      // the DOM), so the server classifies every observation as private.
      // Approve private-page read once, up front; the observe then returns
      // real text instead of :private-read-confirmation-required.
      //
      // The confirmation is a round trip: the click posts, the server
      // answers, the badge re-renders.  A fixed 800 ms sleep sometimes
      // reads the badge before that lands and reports a false negative.
      // Wait for the badge text instead.
      const approveEarly = page.getByRole('button', { name: '确认私密页读取' });
      if (await approveEarly.count()) {
        await approveEarly.first().evaluate((el) => el.click());
      }
      checks.privateReadApproved = await page.getByText('私密页读取：已确认')
        .first()
        .waitFor({ state: 'visible', timeout: 15000 })
        .then(() => true, () => false);

      await observe(page);
      try {
        await waitBrowserText(page, 'E2E14 Step 1', 90000);
      } catch (error) {
        console.log('DEBUG browserText:', JSON.stringify((await browserText(page)).slice(0, 4000)));
        console.log('DEBUG warning:', await page.locator('.hc-browser-warning').innerText().catch(() => 'none'));
        console.log('DEBUG status:', await page.locator('.hc-browser-command').innerText().catch(() => 'none'));
        fs.writeFileSync(path.join(EVIDENCE_DIR, 'debug-ws-frames.txt'),
          wsFrames.join('\n---FRAME---\n'));
        console.log(`DEBUG wsFrames: ${wsFrames.length} -> debug-ws-frames.txt`);
        throw error;
      }
      checks.step1Observed = true;
      evidence.screenshots.push(await shot(page, '03-step1-observed'));
      writeEvidence();

      // --- agent types into step-1 field, then clicks 下一步 -------------
      await selectNode(page, /name|姓名/);
      const typeInput = page.locator('.hc-browser-field')
        .filter({ hasText: '普通文本' }).locator('input');
      await typeInput.fill('代理输入');
      await clickById(page, 'hc-browser-type');
      await observe(page);
      await waitBrowserText(page, '代理输入', 90000);
      checks.agentTyped = true;
      writeEvidence();

      await selectNode(page, /下一步/);
      await clickById(page, 'hc-browser-click');
      await page.waitForTimeout(800);
      await observe(page);
      await waitBrowserText(page, 'E2E14 Step 2', 90000).catch(async (error) => {
        const retry = page.getByRole('button', { name: /重试|重新读取页面/ }).first();
        if ((await retry.count()) === 0) throw error;
        await retry.click().catch(() => {});
        await waitBrowserText(page, 'E2E14 Step 2', 30000);
      });
      checks.step2Reached = true;
      evidence.screenshots.push(await shot(page, '04-step2'));
      writeEvidence();

      // --- operator input pauses the agent -------------------------------
      await managed.bringToFront();
      await managed.locator('#email').click();
      await managed.keyboard.type('human@example.test');
      await page.getByText('检测到手动操作').first()
        .waitFor({ timeout: 30000 });
      checks.operatorPauseDetected = true;
      const typeBtn = page.locator('#hc-browser-type');
      // While paused the server clears the observation, so the whole action
      // row unmounts; an absent button is the strongest "disabled" signal.
      checks.typeButtonWhilePaused =
        (await typeBtn.count()) === 0
        ? 'absent'
        : (await typeBtn.isDisabled() ? 'disabled' : 'enabled');
      checks.agentDisabledWhilePaused = checks.typeButtonWhilePaused !== 'enabled';
      assert(checks.agentDisabledWhilePaused,
             'agent type action stayed enabled during operator pause');
      evidence.screenshots.push(await shot(page, '05-paused-manual'));

      // Resume: the service re-reads the DOM; the typed value must be
      // visible in the fresh observation.
      await clickById(page, 'hc-browser-resume');
      await page.getByText('代理可操作').first().waitFor({ timeout: 30000 });
      await observe(page);
      await waitBrowserText(page, 'human@example.test', 90000);
      checks.resumeRereadDom = true;
      evidence.screenshots.push(await shot(page, '06-resumed'));

      // --- multi-step page cards appear in order -------------------------
      const cardCount = await page.locator('.hc-browser-card').count();
      checks.pageCards = cardCount;
      assert(cardCount >= 3, `expected ordered page cards, got ${cardCount}`);
      const cardSeqs = await page.locator('.hc-browser-card')
        .evaluateAll((nodes) => nodes.map((n) =>
          (n.innerText.match(/#(\d+)/) || [])[1]).filter(Boolean)
          .map(Number));
      checks.cardSeqsAscending = cardSeqs.every((v, i) => i === 0 || v > cardSeqs[i - 1]);
      evidence.screenshots.push(await shot(page, '07-page-cards'));

      // --- sensitive field is never agent-filled -------------------------
      // A background snapshot refresh can invalidate the selected node
      // between observe and click; the server then refuses with a stale
      // target.  Re-observe and retry a bounded number of times.
      checks.step3Reached = false;
      for (let attempt = 0; attempt < 4 && !checks.step3Reached; attempt++) {
        await selectNode(page, /下一步/);
        await clickById(page, 'hc-browser-click');
        await page.waitForTimeout(800);
        await observe(page);
        try {
          await waitBrowserText(page, 'E2E14 Step 3', 10000);
          checks.step3Reached = true;
        } catch {
          // stale click; loop re-observes via selectNode's fresh rows
        }
      }
      assert(checks.step3Reached, 'could not reach step 3 after retries');

      // Node rows are label text only ("hc-N 输入框 密码 密码"); the
      // semantic `.hc-browser-node-sensitive` class pins the real input
      // node instead of the submit-button row that also mentions 密码.
      const sensitiveRow = page
        .locator('.hc-browser-node.hc-browser-node-sensitive').first();
      await sensitiveRow.waitFor({ state: 'visible', timeout: 15000 });
      const sensitiveText = await sensitiveRow.innerText();
      checks.sensitiveLabel = sensitiveText.includes('密码');
      checks.sensitiveBadge = sensitiveText.includes('敏感，操作员输入');
      assert(checks.sensitiveLabel && checks.sensitiveBadge,
             `sensitive row missing label/badge: ${sensitiveText}`);
      await sensitiveRow.getByRole('button', { name: '选择' })
        .evaluate((el) => el.click());
      await page.waitForSelector('.hc-browser-node-selected', { timeout: 10000 });
      checks.typeDisabledForSensitive = await page.locator('#hc-browser-type')
        .isDisabled();
      assert(checks.typeDisabledForSensitive,
             'type stayed enabled on a sensitive field');

      // Server-side refusal: submit inside the sensitive form must be
      // refused and pause the page as :sensitive-paused.
      await clickById(page, 'hc-browser-submit');
      await page.waitForFunction(
        () => {
          const el = document.querySelector('.hc-browser');
          return el && (el.innerText || '').includes('敏感输入等待操作员');
        },
        null, { timeout: 30000 });
      checks.sensitiveRefused = true;
      evidence.screenshots.push(await shot(page, '08-sensitive-paused'));

      // The agent never fills the sensitive field: while :sensitive-paused
      // every agent action is disabled (the stale observation stays on
      // screen but cannot be acted on); the operator types in the real
      // page.  An absent button is also acceptable.
      const sensitiveTypeBtn = page.locator('#hc-browser-type');
      checks.typeButtonWhileSensitivePaused =
        (await sensitiveTypeBtn.count()) === 0
        ? 'absent'
        : (await sensitiveTypeBtn.isDisabled() ? 'disabled' : 'enabled');
      checks.agentDisabledWhileSensitivePaused =
        checks.typeButtonWhileSensitivePaused !== 'enabled';
      assert(checks.agentDisabledWhileSensitivePaused,
             'agent type action stayed enabled during sensitive pause');

      await managed.locator('#pw').click();
      await managed.keyboard.type('s3cret-operator-value');
      await managed.locator('#note').click();
      await managed.keyboard.type('operator note');
      await managed.locator('#pay-submit').click();
      await managed.locator('#done:not([hidden])')
        .waitFor({ state: 'visible', timeout: 15000 });
      checks.operatorFilledSensitive = true;

      // Resume re-reads the DOM; the submitted result must be visible.
      await clickById(page, 'hc-browser-resume');
      await page.getByText('代理可操作').first().waitFor({ timeout: 30000 });
      await observe(page);
      await waitBrowserText(page, '已提交', 90000);
      checks.resumedAfterSensitive = true;
      checks.sensitiveValueNotLeaked =
        !(await browserText(page)).includes('s3cret-operator-value');
      evidence.screenshots.push(await shot(page, '08b-operator-filled'));

      // Dismiss the refusal banner so the post-close check observes a clean
      // pane; the refusal itself is already recorded in page cards.
      const dismiss = page.locator('.hc-browser-warning button', { hasText: '关闭' });
      if (await dismiss.count()) await dismiss.first().evaluate((el) => el.click());
      await page.waitForTimeout(500);

      // --- close tab keeps session + cards --------------------------------
      const cardsBefore = await page.locator('.hc-browser-card').count();
      await page.getByRole('button', { name: '关闭页面', exact: true })
        .first().evaluate((el) => el.click());
      await page.waitForTimeout(1500);
      const bodyText = await browserText(page);
      checks.tabClosedShown = /已关闭/.test(bodyText);
      checks.cardsKeptAfterClose =
        await page.locator('.hc-browser-card').count() >= cardsBefore;
      checks.noErrorAfterClose = !await page.locator('.hc-browser-warning')
        .count();
      // The workbench session itself is untouched: navigate back to
      // sessions and confirm the session row still exists.
      //
      // `waitForShell` only waits for the shell container, not for the
      // session list to receive its first snapshot.  Counting immediately
      // reads an empty list and reports a false negative.  Wait for the row.
      await goto(page, app.url, '/sessions');
      await waitForShell(page);
      const sessionRow = page.locator('.wb-session-list .wb-list-button').first();
      checks.sessionKept = await sessionRow
        .waitFor({ state: 'visible', timeout: 15000 })
        .then(() => true, () => false);
      evidence.screenshots.push(await shot(page, '09-after-close'));

      evidence.ok = Object.values(checks).every(Boolean);
      evidence.finishedAt = new Date().toISOString();
      writeEvidence();
      assert(evidence.ok, `checks failed: ${JSON.stringify(checks)}`);
    } finally {
      await cdp.close().catch(() => {});
    }
  } finally {
    if (prevEndpoint === undefined) delete process.env.HC_BROWSER_ENDPOINT;
    else process.env.HC_BROWSER_ENDPOINT = prevEndpoint;
    if (app) await app.close().catch(() => {});
    chrome.kill('SIGTERM');
    await new Promise((resolve) => {
      const timer = setTimeout(resolve, 3000);
      chrome.once('exit', () => { clearTimeout(timer); resolve(); });
    });
    pageServer.close();
    try { fs.rmSync(chromeData, { recursive: true, force: true }); } catch { /* best effort */ }
    try { fs.rmSync(tempRoot, { recursive: true, force: true }); } catch { /* best effort */ }
    writeEvidence();
  }
});
