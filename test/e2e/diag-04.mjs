// Temporary diagnostic for E2E-04 branch projection. Not part of the suite.
import fs from 'node:fs';
import path from 'node:path';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  composer, ensureIdle, goto, newSession, send, waitForAssistant, waitForSync, writeJson,
} from './support/ui.mjs';

const MODEL_ID = 'mock-vision';
const marker = `DIAG-${Date.now()}`;
const dir = path.join(REPO_ROOT, 'plans/evidence/e2e-04-diag');
fs.mkdirSync(dir, { recursive: true });

const mock = await startMockModel({
  replyScript: [
    { match: '第一轮', reply: `D-REPLY-1 ${marker}` },
    { match: '第二轮', reply: `D-REPLY-2 ${marker}` },
    { match: '第三轮', reply: `D-REPLY-3 ${marker}` },
    { reply: `D-FALLBACK ${marker}` },
  ],
});
const app = await startApp({
  evidenceDir: dir,
  models: {
    connections: [{
      id: 'mock', name: 'Mock 视觉服务', baseUrl: `${mock.url}/v1`,
      models: [{ id: MODEL_ID, displayName: 'Mock Vision', maxOutputTokens: 2048, capabilities: ['vision', 'tools'] }],
    }],
    roles: { main: { connection: 'mock', model: MODEL_ID } },
  },
});

const { chromium } = await import('@playwright/test');
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome' });
const page = await browser.newPage();

const wsLog = [];
page.on('websocket', (ws) => {
  if (!ws.url().includes('/chsk')) return;
  ws.on('framesent', (frame) => {
    const t = typeof frame.payload === 'string' ? frame.payload : frame.payload?.toString?.('utf8');
    if (typeof t === 'string' && t.length < 200000) wsLog.push({ dir: 'sent', text: t });
  });
  ws.on('framereceived', (frame) => {
    const t = typeof frame.payload === 'string' ? frame.payload : frame.payload?.toString?.('utf8');
    if (typeof t === 'string' && t.length < 200000) wsLog.push({ dir: 'recv', text: t });
  });
});
page.on('pageerror', (e) => console.log('PAGEERROR', String(e).slice(0, 300)));
page.on('console', (m) => { if (m.type() === 'error') console.log('CONSOLE', m.text().slice(0, 300)); });

try {
  await goto(page, app.url, '/sessions');
  await waitForSync(page);
  await newSession(page);
  await send(page, `D 第一轮 ${marker}`);
  await waitForAssistant(page, { timeout: 90000, minChars: 5, quietMs: 2000 });
  await ensureIdle(page, 20000);
  await send(page, `D 第二轮 ${marker}`);
  await waitForAssistant(page, { timeout: 90000, minChars: 5, quietMs: 2000 });
  await ensureIdle(page, 20000);
  await send(page, `D 第三轮 ${marker}`);
  await waitForAssistant(page, { timeout: 90000, minChars: 5, quietMs: 2000 });
  await ensureIdle(page, 20000);

  const users = page.locator('.hc-message-out:not(.hc-message-process)');
  console.log('user count', await users.count());
  const firstRow = users.first();
  await firstRow.getByRole('button', { name: '从此处分支', exact: true }).click();
  await page.locator('.wb-branch-origin').waitFor({ state: 'visible', timeout: 30000 });
  await page.waitForTimeout(15000);

  const branchUsers = await page.locator('.hc-message-out:not(.hc-message-process)').count();
  const branchIn = await page.locator('.hc-message-in:not(.hc-message-process)').count();
  console.log('branch user msgs:', branchUsers, 'assistant msgs:', branchIn);

  // dump client DB if exposed
  const dbInfo = await page.evaluate(() => {
    const w = window;
    const keys = Object.keys(w).filter((k) => /HC|app|db/i.test(k));
    return { keys };
  });
  console.log('window keys', dbInfo.keys);

  writeJson(dir, 'diag-ws', wsLog);
  // print branch command payloads from ws frames
  for (const f of wsLog) {
    if (f.text.includes('history/branch') || f.text.includes('before-message-id')) {
      console.log('WS', f.dir, f.text.slice(0, 2000));
    }
  }
} finally {
  writeJson(dir, 'diag-ws', wsLog);
  await browser.close();
  await app.close();
  await mock.close();
}
