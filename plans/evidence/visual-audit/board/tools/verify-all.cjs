const { chromium } = require('playwright');
const { spawn, execSync } = require('child_process');
const http = require('http');
const fs = require('fs');
const path = require('path');

const OUT = '/tmp/hc-visual-board/shots';
const APP = 'http://127.0.0.1:7411';
const FIX = 'http://127.0.0.1:7412/index.html';
const CHROME_PORT = 9223;
const CHROME_DIR = '/tmp/hc-visual-board/chrome-profile';
fs.mkdirSync(OUT, { recursive: true });
fs.rmSync(CHROME_DIR, { recursive: true, force: true });

function waitHttp(url, timeoutMs = 90000) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      const req = http.get(url, (res) => { res.resume(); resolve(res.statusCode); });
      req.on('error', () => {
        if (Date.now() - started > timeoutMs) reject(new Error('timeout ' + url));
        else setTimeout(tick, 200);
      });
      req.setTimeout(1000, () => req.destroy());
    };
    tick();
  });
}

async function shot(page, name, fullPage = true) {
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(OUT, name + '.png'), fullPage });
  console.log('SHOT', name);
}

async function theme(page, value) {
  await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), value);
  await page.waitForTimeout(150);
}

async function fontScale(page, value) {
  await page.evaluate((v) => document.documentElement.style.setProperty('--wb-font-scale', String(v)), value);
  await page.waitForTimeout(200);
}

(async () => {
  const chrome = spawn('/usr/bin/google-chrome', [
    '--headless=new', `--remote-debugging-port=${CHROME_PORT}`,
    '--remote-allow-origins=*', `--user-data-dir=${CHROME_DIR}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--disable-dev-shm-usage', '--no-sandbox', 'about:blank'
  ], { stdio: ['ignore', 'ignore', 'ignore'] });
  fs.writeFileSync('/tmp/hc-visual-board/chrome.pid', String(chrome.pid));
  console.log('CHROME_PID=' + chrome.pid);
  await waitHttp(`http://127.0.0.1:${CHROME_PORT}/json/version`);
  const browser = await chromium.connectOverCDP(`http://127.0.0.1:${CHROME_PORT}`);
  const context = browser.contexts()[0] || await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(25000);
  page.on('pageerror', e => console.log('PAGEERROR', e.message));
  page.on('console', m => { if (m.type() === 'error') console.log('CONSOLE', m.text()); });

  const fix = async (route, scenario, name, fullPage = true) => {
    await page.goto(`${FIX}?scenario=${scenario}${route}`, { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => {
      document.documentElement.setAttribute('data-theme', 'light');
      document.documentElement.style.setProperty('--wb-font-scale', '1');
    });
    await page.waitForTimeout(350);
    await shot(page, name, fullPage);
  };

  // ---- app
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto(APP + '/#/board', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.wb-shell', { timeout: 90000 });
  await page.waitForTimeout(1600);
  await shot(page, 'app-board-empty-1280');
  console.log('APP_BOARD_TEXT', JSON.stringify((await page.locator('.wb-content').innerText()).slice(0, 500)));
  await theme(page, 'dark');
  await shot(page, 'app-board-empty-dark-1280');
  await theme(page, 'light');
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto(APP + '/#/board', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(600);
  await shot(page, 'app-board-empty-360');

  await page.goto(APP + '/#/materials', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(900);
  await shot(page, 'app-files-empty-1280');
  const browserTab = page.locator('button').filter({ hasText: /^浏览器$/ }).first();
  if (await browserTab.count()) { await browserTab.click(); await page.waitForTimeout(600); }
  await shot(page, 'app-browser-offline-1280');
  console.log('APP_BROWSER_TEXT', JSON.stringify((await page.locator('.wb-content').innerText()).slice(0, 700)));
  await page.setViewportSize({ width: 360, height: 800 });
  await page.waitForTimeout(400);
  await shot(page, 'app-browser-offline-360');
  await page.setViewportSize({ width: 1280, height: 800 });
  const artTab = page.locator('button').filter({ hasText: /^产物$/ }).first();
  if (await artTab.count()) { await artTab.click(); await page.waitForTimeout(500); }
  await shot(page, 'app-artifacts-empty-1280');

  await page.goto(APP + '/#/sessions', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(900);
  await shot(page, 'app-search-closed-1280');
  const searchToggle = page.locator('.hc-search-controls button').filter({ hasText: /展开/ }).first();
  if (await searchToggle.count()) {
    await searchToggle.click();
    await page.waitForTimeout(400);
    await shot(page, 'app-search-open-1280');
    console.log('APP_SEARCH_TEXT', JSON.stringify((await page.locator('.hc-search-controls').innerText()).slice(0, 600)));
  }

  // ---- fixture: board
  await page.setViewportSize({ width: 1280, height: 900 });
  await fix('#/board', 'rich', 'board-rich-1280');
  const joined = page.locator('.hc-swarm-row[data-status="joined"] summary').first();
  if (await joined.count()) {
    await joined.click(); await page.waitForTimeout(300);
    const report = page.locator('.hc-join-report summary').first();
    if (await report.count()) { await report.click(); await page.waitForTimeout(200); }
    await page.locator('.hc-join-report').first().scrollIntoViewIfNeeded().catch(() => {});
    await shot(page, 'board-rich-joined-open-1280');
  }
  const failed = page.locator('.hc-swarm-row[data-status="failed"] summary').first();
  if (await failed.count()) { await failed.click(); await page.waitForTimeout(200); await failed.scrollIntoViewIfNeeded().catch(() => {}); }
  await shot(page, 'board-rich-failed-open-1280');
  await fix('#/board', 'empty', 'board-empty-1280');
  await fix('#/board', 'board-only', 'board-board-only-1280');
  await fix('#/board', 'recovery', 'board-recovery-1280');
  await fix('#/board', 'long', 'board-long-1280');
  await page.setViewportSize({ width: 820, height: 1180 });
  await fix('#/board', 'rich', 'board-rich-820');
  await page.setViewportSize({ width: 360, height: 800 });
  await fix('#/board', 'rich', 'board-rich-360');
  await page.setViewportSize({ width: 1280, height: 900 });
  await fix('#/board', 'rich', 'board-rich-light-1280');
  await theme(page, 'dark');
  await shot(page, 'board-rich-dark-1280');
  await theme(page, 'light');
  await fontScale(page, 2);
  await shot(page, 'board-rich-200pct-1280');
  await page.setViewportSize({ width: 360, height: 800 });
  await shot(page, 'board-rich-200pct-360');
  await page.setViewportSize({ width: 1280, height: 900 });
  await fontScale(page, 1);

  // ---- fixture: browser
  await page.setViewportSize({ width: 1280, height: 900 });
  await fix('#/browser', 'offline', 'browser-offline-1280');
  await fix('#/browser', 'permission', 'browser-permission-1280');
  await fix('#/browser', 'takeover', 'browser-takeover-1280');
  await page.setViewportSize({ width: 360, height: 800 });
  await fix('#/browser', 'permission', 'browser-permission-360');
  await page.setViewportSize({ width: 1280, height: 900 });
  await fix('#/browser', 'permission', 'browser-permission-light-1280');
  await theme(page, 'dark');
  await shot(page, 'browser-permission-dark-1280');
  await theme(page, 'light');
  await fontScale(page, 2);
  await shot(page, 'browser-permission-200pct-1280');
  await fontScale(page, 1);

  // ---- fixture: files
  await fix('#/files', 'rich', 'files-rich-1280');
  await fix('#/files', 'conflict', 'files-conflict-1280');
  await fix('#/files', 'error', 'files-error-1280');
  await fix('#/files', 'search', 'files-search-1280');
  await fix('#/files', 'empty', 'files-empty-1280');
  await page.setViewportSize({ width: 820, height: 1180 });
  await fix('#/files', 'rich', 'files-rich-820');
  await page.setViewportSize({ width: 360, height: 800 });
  await fix('#/files', 'rich', 'files-rich-360');
  await page.locator('.hc-files-preview').scrollIntoViewIfNeeded().catch(() => {});
  await shot(page, 'files-rich-360-preview');
  await page.setViewportSize({ width: 1280, height: 900 });
  await fix('#/files', 'rich', 'files-rich-light-1280');
  await theme(page, 'dark');
  await shot(page, 'files-rich-dark-1280');
  await theme(page, 'light');
  await fontScale(page, 2);
  await shot(page, 'files-rich-200pct-1280');
  await page.setViewportSize({ width: 360, height: 800 });
  await shot(page, 'files-rich-200pct-360');
  await page.setViewportSize({ width: 1280, height: 900 });
  await fontScale(page, 1);

  // ---- fixture: artifacts
  await fix('#/artifacts', 'rich', 'artifacts-rich-1280');
  await page.setViewportSize({ width: 360, height: 800 });
  await fix('#/artifacts', 'rich', 'artifacts-rich-360');
  await page.setViewportSize({ width: 1280, height: 900 });
  await fix('#/artifacts', 'rich', 'artifacts-rich-light-1280');
  await theme(page, 'dark');
  await shot(page, 'artifacts-rich-dark-1280');
  await theme(page, 'light');

  // ---- fixture: search
  await fix('#/search', 'sources', 'search-sources-1280');
  await fix('#/search', 'error', 'search-error-1280');
  await fix('#/search', 'empty', 'search-empty-1280');
  await fix('#/search', 'closed', 'search-closed-1280');
  await page.setViewportSize({ width: 360, height: 800 });
  await fix('#/search', 'sources', 'search-sources-360');
  await page.setViewportSize({ width: 1280, height: 900 });
  await fix('#/search', 'sources', 'search-sources-light-1280');
  await theme(page, 'dark');
  await shot(page, 'search-sources-dark-1280');
  await theme(page, 'light');
  await fontScale(page, 2);
  await shot(page, 'search-sources-200pct-1280');
  await fontScale(page, 1);

  await browser.close().catch(() => {});
  chrome.kill('SIGTERM');
  console.log('ALL_DONE');
})().catch(e => {
  console.error(e);
  try { execSync('pkill -f -- "--user-data-dir=' + CHROME_DIR + '" || true'); } catch (_) {}
  process.exit(1);
});
