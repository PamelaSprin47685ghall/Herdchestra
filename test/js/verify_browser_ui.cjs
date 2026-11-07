const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '../..');
const DATA = `/tmp/hc-browser-ui-${process.pid}`;
const WS = `${DATA}-ws`;
const CHROME_DATA = `${DATA}-chrome`;
fs.mkdirSync(DATA, { recursive: true });
fs.mkdirSync(WS, { recursive: true });

function freePort() {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
  });
}

function waitHttp(url, timeoutMs = 90000) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      const request = http.get(url, (response) => {
        response.resume();
        resolve(response.statusCode);
      });
      request.on('error', () => {
        if (Date.now() - started > timeoutMs) reject(new Error(`timeout ${url}`));
        else setTimeout(tick, 250);
      });
      request.setTimeout(1000, () => request.destroy());
    };
    tick();
  });
}

async function actionButton(page, id, text) {
  const byId = page.locator(id);
  if (await byId.count()) return byId.first();
  return page.locator('button').filter({ hasText: text }).first();
}

const pageHtml = `<!doctype html><html><head><meta charset="utf-8"><title>UI Harbor Form</title>
<script>window.__hcAgentInput = true; window.__hcOperatorInput = function(){};</script>
</head><body>
<h1 id="step">UI Step 1</h1>
<label for="email">Email</label>
<input id="email" name="email" type="email" oninput="document.getElementById('mirror').textContent=this.value">
<div id="mirror"></div>
<label for="password">Password</label>
<input id="password" name="password" type="password">
<button id="advance" type="button" onclick="document.getElementById('step').textContent='UI Done'">Advance</button>
</body></html>`;

(async () => {
  const chromePort = await freePort();
  const appPort = await freePort();
  const pagePort = await freePort();
  console.log('ports', { chromePort, appPort, pagePort });

  const pageServer = http.createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(pageHtml);
  });
  await new Promise((resolve) => pageServer.listen(pagePort, '127.0.0.1', resolve));

  const chrome = spawn('/usr/bin/google-chrome', [
    '--headless=new', `--remote-debugging-port=${chromePort}`,
    '--remote-allow-origins=*', `--user-data-dir=${CHROME_DATA}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', 'about:blank'
  ], { stdio: ['ignore', 'ignore', 'ignore'] });
  await waitHttp(`http://127.0.0.1:${chromePort}/json/version`);

  const appLog = [];
  const app = spawn('clojure', ['-M:server', '-m', 'hc.server.app',
    '--host', '127.0.0.1', '--port', String(appPort),
    '--data-dir', DATA, '--workspace-root', WS,
    '--static-root', path.join(ROOT, 'resources/public'),
    '--log-level', 'warn'],
    { cwd: ROOT, env: { ...process.env, HC_BROWSER_ENDPOINT: `http://127.0.0.1:${chromePort}` } });
  app.stdout.on('data', (d) => appLog.push(d.toString()));
  app.stderr.on('data', (d) => appLog.push(d.toString()));

  try {
    await waitHttp(`http://127.0.0.1:${appPort}/healthz`);
    console.log('app ready');
    const browser = await chromium.connectOverCDP(`http://127.0.0.1:${chromePort}`);
    const context = browser.contexts()[0] || await browser.newContext();
    const page = await context.newPage();
    global.__hcSmokePage = page;
    await page.setViewportSize({ width: 1440, height: 1000 });
    page.setDefaultTimeout(20000);
    page.on('pageerror', (error) => console.log('PAGE ERROR:', error.message));
    await page.goto(`http://127.0.0.1:${appPort}/#/materials`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.wb-shell', { timeout: 60000 });
    await page.locator('button').filter({ hasText: /^浏览器$/ }).first().click();
    await page.waitForSelector('.hc-browser');
    await page.waitForSelector('#hc-browser-enable, #hc-browser-type', { timeout: 30000 });
    await page.waitForTimeout(1000);
    if (await page.getByText(/explicit :session-id/).count()) {
      throw new Error('browser bridge sent a command without :session-id');
    }
    console.log('browser pane mounted');

    if (await page.locator('#hc-browser-enable').count()) {
      await page.locator('#hc-browser-site').fill('127.0.0.1');
      await page.locator('.hc-browser-field').filter({ hasText: '允许范围' }).locator('select')
        .selectOption({ label: '指定站点' });
      await (await actionButton(page, '#hc-browser-enable', '启用浏览器')).evaluate((el) => el.click());
      await page.waitForFunction(() => !document.querySelector('#hc-browser-enable'), null, { timeout: 30000 });
    }
    console.log('permission enabled');

    await page.getByLabel('浏览器地址').fill(`http://127.0.0.1:${pagePort}/`);
    await (await actionButton(page, '#hc-browser-open', '新标签打开')).evaluate((el) => el.click());
    await page.waitForFunction(() => document.body.innerText.includes('UI Harbor Form'), null, { timeout: 30000 });
    await (await actionButton(page, '#hc-browser-observe', '观察')).evaluate((el) => el.click());
    await page.waitForFunction(() => document.querySelector('.hc-browser-text')?.innerText.includes('UI Step 1'), null, { timeout: 30000 });
    const typeButton = await actionButton(page, '#hc-browser-type', '输入选中');
    console.log('type button count', await typeButton.count());
    await typeButton.waitFor({ state: 'visible', timeout: 30000 });
    console.log('observed page');

    // Real human input in the controlled page must pause the agent without
    // the "报告手动操作" button.  Playwright drives the managed tab directly.
    const managed = context.pages().find((candidate) =>
      candidate.url().startsWith(`http://127.0.0.1:${pagePort}/`));
    if (!managed) throw new Error('managed page not found');
    await managed.bringToFront();
    await managed.locator('#email').click();
    await managed.keyboard.type('human@example.test');
    await page.getByText('检测到手动操作').first().waitFor({ timeout: 30000 });
    const autoType = await actionButton(page, '#hc-browser-type', '输入选中');
    const autoTypePresent = await autoType.count();
    if (autoTypePresent && !(await autoType.isDisabled())) {
      throw new Error('agent actions were not paused after human input');
    }
    console.log('auto-paused on human input', autoTypePresent);
    await (await actionButton(page, '#hc-browser-resume', '恢复代理')).evaluate((el) => el.click());
    await page.getByText('代理可操作').first().waitFor({ timeout: 30000 });
    await page.waitForFunction(() => document.querySelector('.hc-browser-text')?.innerText.includes('human@example.test'), null, { timeout: 30000 });
    console.log('human input preserved after resume');

    const emailRow = page.locator('.hc-browser-node').filter({ hasText: 'input email' }).first();
    await emailRow.getByRole('button', { name: '选择' }).evaluate((el) => el.click());
    await page.waitForSelector('.hc-browser-node-selected');
    const typeInput = page.locator('.hc-browser-field').filter({ hasText: '普通文本' }).locator('input');
    await typeInput.fill('ui@example.test');
    await typeButton.evaluate((el) => el.click());
    await page.waitForFunction(() => !document.querySelector('#hc-browser-type')?.disabled, null, { timeout: 30000 }).catch(() => {});
    await (await actionButton(page, '#hc-browser-observe', '观察')).evaluate((el) => el.click());
    await page.waitForFunction(() => document.querySelector('.hc-browser-text')?.innerText.includes('ui@example.test'), null, { timeout: 30000 });
    console.log('typed state visible');

    const advanceRow = page.locator('.hc-browser-node').filter({ hasText: 'button Advance' }).first();
    await advanceRow.getByRole('button', { name: '选择' }).evaluate((el) => el.click());
    await page.waitForSelector('.hc-browser-node-selected');
    await (await actionButton(page, '#hc-browser-click', '点击选中')).evaluate((el) => el.click());
    await (await actionButton(page, '#hc-browser-observe', '观察')).evaluate((el) => el.click());
    await page.waitForFunction(() => document.querySelector('.hc-browser-text')?.innerText.includes('UI Done'), null, { timeout: 30000 });
    console.log('click action visible');

    await (await actionButton(page, '#hc-browser-takeover', '我来操作')).evaluate((el) => el.click());
    await page.getByText('操作员控制').first().waitFor({ timeout: 30000 });
    const takeoverType = await actionButton(page, '#hc-browser-type', '输入选中');
    const typePresent = await takeoverType.count();
    const typeDisabled = typePresent ? await takeoverType.isDisabled() : true;
    console.log('takeover type present/disabled', typePresent, typeDisabled);
    if (!typeDisabled) {
      throw new Error('type action was not disabled during takeover');
    }
    await (await actionButton(page, '#hc-browser-resume', '恢复代理')).evaluate((el) => el.click());
    await page.getByText('代理可操作').first().waitFor({ timeout: 30000 });
    await page.waitForFunction(() => document.querySelector('.hc-browser-text')?.innerText.includes('ui@example.test'), null, { timeout: 30000 });
    console.log('resume preserved typed state');

    const cards = await page.locator('.hc-browser-card').count();
    if (cards < 3) throw new Error(`expected page cards, got ${cards}`);
    console.log('page cards', cards);
    console.log('SMOKE OK');
    await page.close();
    await browser.close();
  } catch (error) {
    console.error('SMOKE FAILED', error);
    try {
      console.error('PANE', (await global.__hcSmokePage.locator('.hc-browser').innerText()).slice(0, 3000));
    } catch (_) {
      console.error('PANE unavailable');
    }
    console.error('APP LOG', appLog.join('').slice(-4000));
    process.exitCode = 1;
  } finally {
    app.kill('SIGTERM');
    chrome.kill('SIGTERM');
    pageServer.close();
  }
})();
