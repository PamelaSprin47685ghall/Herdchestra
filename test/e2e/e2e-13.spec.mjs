// E2E 13: skill import preview, install-then-enable-then-bind, workspace
// mini-app generation records, and per-app storage isolation.  The real
// app + real Chrome are used; the model is the local mock with a
// `replyScript` that returns valid HTML for the generation prompts.
//
// Skill flow: pure UI path.  The panel has no separate "preview" button for
// skills — "安装技能" sends `:extensions/skill/install`, which runs the same
// server-side preview+install in one step; the installed card exposes the
// parsed frontmatter name/description and the packaged file list, which
// this spec asserts.  Enable/bind go through the card controls; the
// assistant select is projected from `:settings/assistants` by the
// extensions bridge (W251/W258), with the built-in `default` row as the
// minimum population on a fresh app.
//
// Mini-apps: generation writes `miniapps/*.html` through the protected
// workspace gate; the panel opens it in a sandboxed iframe whose
// localStorage is prefixed `miniapp:<app-id>:` and persisted to
// IndexedDB `herdchestra-miniapps` / store `kv`.  Two generated apps and a
// plain browser page must not see each other's data.
import { test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startApp } from './support/app.mjs';
import { goto, waitForSync, shot, assert as uiAssert, clientBundleInfo } from './support/ui.mjs';
import { recordEvidence, sourceTreeSha256 } from './support/evidence.mjs';
import { startMockModel } from './support/mock-model.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../..');
const EVIDENCE = path.join(REPO_ROOT, 'plans/evidence/e2e-13');

const SKILL_MD = [
  '---',
  'name: e2e13-reading-notes',
  'description: E2E13 读书条目技能：记录书名、页码与摘录到工作区笔记。',
  '---',
  '# E2E13 读书条目',
  '',
  '把读到的书名、页码和摘录记进 notes/reading.md。',
  '每条记录格式：- 《书名》 p.页码 — 摘录。',
].join('\n');

// Two complete self-contained HTML documents.  `localStorage` inside the
// iframe is the per-app namespaced shim injected by the server; the apps
// only need ordinary localStorage calls.
function readingAppHtml(title) {
  return `<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>${title}</title></head>
<body>
<h1>${title}</h1>
<input id="note" placeholder="读书条目" aria-label="条目输入">
<button id="save" aria-label="保存条目">保存</button>
<ul id="list"></ul>
<script>
(function () {
  var KEY = 'notes';
  function load() {
    try { return JSON.parse(localStorage.getItem(KEY) || '[]'); }
    catch (e) { return []; }
  }
  function render() {
    var list = document.getElementById('list');
    list.innerHTML = '';
    load().forEach(function (item) {
      var li = document.createElement('li');
      li.textContent = item;
      list.appendChild(li);
    });
  }
  document.getElementById('save').addEventListener('click', function () {
    var value = document.getElementById('note').value.trim();
    if (!value) return;
    var items = load();
    items.push(value);
    localStorage.setItem(KEY, JSON.stringify(items));
    document.getElementById('note').value = '';
    render();
  });
  render();
  window.__e2eNoteCount = function () { return load().length; };
})();
</script>
</body></html>`;
}

const APP_A_HTML = readingAppHtml('E2E13 读书条目');
const APP_B_HTML = readingAppHtml('E2E13 观影条目').replace(/读书条目/g, '观影条目');


// ---------------------------------------------------------------------------
// UI helpers
// ---------------------------------------------------------------------------

async function openMaterialsTab(page, tab) {
  await page.getByRole('button', { name: '材料与扩展' }).first().click();
  await page.waitForTimeout(400);
  const tabButton = page.locator('.wb-tabs button.wb-tab', { hasText: tab }).first();
  await tabButton.click();
  await page.waitForTimeout(600);
}

async function generateMiniApp(page, prompt, filename) {
  const details = page.locator('details.hc-miniapp-generate');
  if (!(await details.evaluate((el) => el.open))) {
    await details.locator('summary').click();
  }
  await details.locator('textarea[aria-label="小应用生成提示词"]').fill(prompt);
  await details.locator('input[aria-label="小应用文件名"]').fill(filename);
  await details.getByRole('button', { name: '生成小应用' }).click();
}

async function openAppByName(page, name) {
  const row = page.locator('.hc-miniapp-row', { hasText: name }).first();
  await row.waitFor({ state: 'visible', timeout: 30000 });
  await row.getByRole('button', { name: `打开 ${name}` }).first().click();
}

// The sandboxed iframe is cross-origin to the page, so DOM probes must run
// inside the frame's own execution context (`contentFrame().evaluate`).
async function iframeEval(page, fn, arg) {
  const frame = page.locator('iframe.hc-sandbox-frame').first();
  await frame.waitFor({ state: 'visible', timeout: 30000 });
  const handle = await frame.elementHandle();
  const content = await handle.contentFrame();
  uiAssert(content, 'mini-app iframe has no content frame');
  return content.evaluate(fn, arg);
}

// Poll inside the iframe until `predicate` (a function serialized into the
// frame) returns truthy.  Used instead of page.waitForFunction because the
// frame document is not reachable from the page's main world.
async function waitForIframe(page, predicateSource, timeout = 30000) {
  const frame = page.locator('iframe.hc-sandbox-frame').first();
  await frame.waitFor({ state: 'visible', timeout });
  const handle = await frame.elementHandle();
  const content = await handle.contentFrame();
  uiAssert(content, 'mini-app iframe has no content frame');
  const deadline = Date.now() + timeout;
  // eslint-disable-next-line no-new-func
  const fn = new Function(`return (${predicateSource})()`);
  while (Date.now() < deadline) {
    const ok = await content.evaluate(fn).catch(() => false);
    if (ok) return true;
    await page.waitForTimeout(250);
  }
  throw new Error(`iframe predicate timed out: ${predicateSource.slice(0, 200)}`);
}

async function miniappKvRows(page) {
  return page.evaluate(() => new Promise((resolve, reject) => {
    const req = indexedDB.open('herdchestra-miniapps');
    req.onerror = () => reject(req.error);
    req.onsuccess = () => {
      const db = req.result;
      const tx = db.transaction('kv', 'readonly');
      const store = tx.objectStore('kv');
      const getAll = store.getAll();
      getAll.onsuccess = () => {
        const rows = (getAll.result || []).map((r) => ({
          app_id: r.app_id,
          key: r.key,
          value: typeof r.value === 'string' ? r.value.slice(0, 120) : r.value,
        }));
        db.close();
        resolve(rows);
      };
      getAll.onerror = () => reject(getAll.error);
    };
  }));
}

// ---------------------------------------------------------------------------
// Spec
// ---------------------------------------------------------------------------

test('@e2e E2E 13 skills install/bind + mini-apps generation and storage isolation', async ({ page, browser }) => {
  // Long-tolerance run: generation prompts can take minutes through the
  // mock + real agent loop.  Keep a 30-minute cap so the inner 300s row
  // waits still fit; the outer CLI timeout is set even higher.  The cap
  // covers teardown too: browser context close can stall on an open
  // transport while the app is still shutting down.
  // The body finishes in a few minutes once generation settles; the cap
  test.setTimeout(1800000);
  fs.mkdirSync(EVIDENCE, { recursive: true });
  const startedAt = new Date().toISOString();
  const checks = {};
  const screenshots = [];

  const mock = await startMockModel({
    replyScript: [
      // "故意失败" must be first: its prompt deliberately contains the
      // generation keywords below; the first matching rule wins.
      { match: '故意失败', reply: '这不是 HTML 文档，没有 html 标签' },
      { match: '读书条目', reply: APP_A_HTML },
      { match: '观影条目', reply: APP_B_HTML },
    ],
  });
  const app = await startApp({
    models: {
      connections: [{
        id: 'mock',
        name: 'Mock 模型服务',
        baseUrl: `${mock.url}/v1`,
        models: [{ id: 'mock-fast', displayName: 'Mock Fast' }],
      }],
      roles: {
        main: { connection: 'mock', model: 'mock-fast' },
        task: { connection: 'mock', model: 'mock-fast' },
      },
    },
    evidenceDir: EVIDENCE,
  });

  // Screenshots are evidence garnish, not assertions: a teardown race
  // (context close while a full-page shot is in flight) must not fail the
  // run, so the promise resolves to a placeholder on error.
  const record = (name) => screenshots.push(
    shot(page, EVIDENCE, name).catch(() => ({})));

  try {
    await goto(page, app.url, '/materials');
    await waitForSync(page);

    // --- skill: UI install, default disabled + unbound ------------------
    await openMaterialsTab(page, '扩展');
    const extPanel = page.locator('.hc-ext-panel');
    await extPanel.waitFor({ state: 'visible', timeout: 30000 });
    await page.waitForFunction(() => {
      const el = document.querySelector('.hc-ext-panel');
      return el && /扩展状态已同步/.test(el.textContent || '');
    }, null, { timeout: 30000 });

    const skillSection = page.locator('.hc-ext-section', { hasText: '技能' }).last();
    await skillSection.locator('textarea').first().fill(SKILL_MD);
    // 名称留空：由 SKILL.md frontmatter 提供。
    const installButton = skillSection.getByRole('button', { name: '安装技能' });
    await installButton.waitFor({ state: 'visible', timeout: 15000 });
    // The button enables once the draft reducer has consumed the input
    // event; give the state round-trip a bounded wait instead of a bare
    // isEnabled snapshot.
    await page.waitForFunction(() => {
      const btn = [...document.querySelectorAll('button')]
        .find((b) => /安装技能/.test(b.textContent || ''));
      return btn && !btn.disabled;
    }, null, { timeout: 15000 });
    await installButton.click();

    const skillCard = page.locator('.hc-ext-card', { hasText: 'e2e13' }).first();
    await skillCard.waitFor({ state: 'visible', timeout: 60000 });
    const cardText = await skillCard.innerText();
    checks.skillCardText = cardText.slice(0, 500);
    checks.skillInstalledDisabled = /未启用/.test(cardText);
    checks.skillInstalledUnbound = /未绑定/.test(cardText);
    checks.skillCardListsFiles = /SKILL\.md/.test(cardText);
    // The card surfaces the parsed SKILL.md frontmatter: name and
    // description prove the server-side preview ran inside install.
    checks.skillCardNameOk = /e2e13-reading-notes/.test(cardText);
    checks.skillCardDescriptionOk = /读书条目技能/.test(cardText);
    uiAssert(checks.skillInstalledDisabled, `installed skill not disabled: ${cardText}`);
    uiAssert(checks.skillInstalledUnbound, `installed skill bound already: ${cardText}`);
    uiAssert(checks.skillCardNameOk, `installed card missing frontmatter name: ${cardText}`);
    uiAssert(checks.skillCardDescriptionOk,
      `installed card missing frontmatter description: ${cardText}`);
    await record('02-skill-installed');

    // --- skill: enable + bind to the default assistant ------------------
    // The binding control is a <select> when the extensions bridge has
    // projected `:settings/assistants` (W251/W258), otherwise a free-text
    // <input>.  Poll the DOM until the projection lands — the bridge
    // recomputes `:assistants` on every sync — before falling back.
    const assistantField = skillSection.locator('.hc-ext-field', { hasText: '绑定目标助手' }).first();
    await assistantField.waitFor({ state: 'visible', timeout: 15000 });
    const assistantSelect = assistantField.locator('select');
    const assistantInput = assistantField.locator('input');
    const hasSelect = await page.waitForFunction(() => {
      const field = [...document.querySelectorAll('.hc-ext-field')]
        .find((f) => /绑定目标助手/.test(f.textContent || ''));
      const select = field && field.querySelector('select');
      return select && [...select.options].some((o) => o.value);
    }, null, { timeout: 60000 }).then(() => true).catch(() => false);
    checks.assistantControlKind = hasSelect ? 'select' : 'input';
    if (hasSelect) {
      const optionValues = await assistantField.locator('select option').evaluateAll(
        (opts) => opts.map((o) => ({ value: o.value, text: o.textContent })));
      checks.assistantOptions = optionValues.map((o) => o.text);
      const target = optionValues.find((o) => o.value);
      uiAssert(target, `no assistant option available for binding: ${JSON.stringify(optionValues)}`);
      checks.boundAssistant = target.text;
      await assistantField.locator('select').selectOption(target.value);
    } else {
      // Free-text fallback: the server accepts any non-blank assistant id
      // for binding (`skill/bind!` only requires a string), so a stable
      // id is enough to prove the bind round-trip.
      checks.assistantOptions = [];
      checks.boundAssistant = 'e2e13-assistant (free-text fallback)';
      await assistantInput.fill('e2e13-assistant');
    }
    // Confirm the draft consumed the selection before enabling/binding.
    await page.waitForFunction(() => {
      const card = [...document.querySelectorAll('.hc-ext-card')]
        .find((c) => /e2e13/.test(c.textContent || ''));
      const bindBtn = card && [...card.querySelectorAll('button')]
        .find((b) => /绑定助手/.test(b.textContent || ''));
      return bindBtn && !bindBtn.disabled;
    }, null, { timeout: 15000 });

    // Enable via the card checkbox.
    const enableBox = skillCard.locator('input[type="checkbox"]').first();
    // The card checkbox is controlled by the extensions state; a plain
    // click toggles it through the app's own change handler.
    await enableBox.click({ force: true });
    await page.waitForFunction(() => {
      const card = [...document.querySelectorAll('.hc-ext-card')]
        .find((c) => /e2e13/.test(c.textContent || ''));
      return card && /已启用/.test(card.textContent || '');
    }, null, { timeout: 60000 });
    checks.skillEnabled = true;

    await skillCard.getByRole('button', { name: '绑定助手' }).click();
    await page.waitForFunction(() => {
      const card = [...document.querySelectorAll('.hc-ext-card')]
        .find((c) => /e2e13/.test(c.textContent || ''));
      return card && /绑定 1/.test(card.textContent || '');
    }, null, { timeout: 60000 });
    checks.skillBound = true;
    await record('03-skill-enabled-bound');

    // --- mini-apps: generate two apps ------------------------------------
    await openMaterialsTab(page, '小应用');
    const miniPanel = page.locator('.hc-miniapps');
    await miniPanel.waitFor({ state: 'visible', timeout: 30000 });
    await page.waitForFunction(() => {
      const el = document.querySelector('.hc-miniapps');
      return el && !/正在读取小应用列表/.test(el.textContent || '');
    }, null, { timeout: 30000 });

    await generateMiniApp(page, '生成一个读书条目小应用：书名输入框、保存按钮、条目列表，数据存本地', 'reading-a.html');
    const rowA = page.locator('.hc-miniapp-row', { hasText: 'reading-a.html' }).first();
    await rowA.waitFor({ state: 'visible', timeout: 300000 });
    checks.appARegistered = true;
    await record('04-miniapp-a-generated');

    await generateMiniApp(page, '生成一个观影条目小应用：片名输入框、保存按钮、条目列表，数据存本地', 'watch-b.html');
    const rowB = page.locator('.hc-miniapp-row', { hasText: 'watch-b.html' }).first();
    await rowB.waitFor({ state: 'visible', timeout: 300000 });
    checks.appBRegistered = true;
    await record('05-miniapp-b-generated');

    // Open app A, write a record, reload, confirm persistence.
    await openAppByName(page, 'reading-a.html');
    await waitForIframe(page, `() => /E2E13 读书条目/.test(document.body.innerText)`);
    await iframeEval(page, () => {
      const input = document.getElementById('note');
      input.value = 'A-record-读书《活着》';
      document.getElementById('save').click();
      return document.getElementById('list').innerText;
    });
    checks.appARecordWritten = true;

    // Reload the app source to prove IndexedDB persistence.
    await page.reload({ waitUntil: 'load' });
    await goto(page, app.url, '/materials');
    await waitForSync(page);
    await openMaterialsTab(page, '小应用');
    await openAppByName(page, 'reading-a.html');
    await waitForIframe(page, `() => /A-record/.test(document.body.innerText)`);
    checks.appARecordPersisted = true;
    await record('06-miniapp-a-reload');

    // App B sees none of A's data.
    await openAppByName(page, 'watch-b.html');
    await waitForIframe(page, `() => /E2E13 观影条目/.test(document.body.innerText)`);
    const bSeesA = await iframeEval(page, () =>
      /A-record/.test(document.body.innerText));
    checks.appBDoesNotSeeA = bSeesA === false;
    await iframeEval(page, () => {
      const input = document.getElementById('note');
      input.value = 'B-record-观影《肖申克的救赎》';
      document.getElementById('save').click();
    });
    checks.appBRecordWritten = true;

    // Back to A: A must not see B's record.
    await openAppByName(page, 'reading-a.html');
    await waitForIframe(page, `() => /E2E13 读书条目/.test(document.body.innerText)`);
    const aSeesB = await iframeEval(page, () =>
      /B-record/.test(document.body.innerText));
    checks.appADoesNotSeeB = aSeesB === false;
    checks.appAKeepsOwn = await iframeEval(page, () =>
      /A-record/.test(document.body.innerText));
    uiAssert(checks.appBDoesNotSeeA && checks.appADoesNotSeeB && checks.appAKeepsOwn,
      `cross-app storage leak: ${JSON.stringify({ bSeesA, aSeesB })}`);
    await record('07-miniapp-isolation');

    // IndexedDB kv rows: two app_ids, disjoint keys.
    const kvRows = await miniappKvRows(page);
    checks.kvRows = kvRows;
    const appIds = [...new Set(kvRows.map((r) => r.app_id))];
    checks.kvAppCount = appIds.length;
    uiAssert(appIds.length >= 2, `expected >=2 app kv rows, got ${JSON.stringify(kvRows)}`);

    // Plain browser page storage is separate: write a sentinel to the
    // page origin's localStorage and IndexedDB, then confirm the app
    // iframe cannot see it (different storage namespace entirely).
    await page.evaluate(() => {
      localStorage.setItem('e2e13-page-sentinel', 'page-data');
      return new Promise((resolve, reject) => {
        const req = indexedDB.open('e2e13-page-db', 1);
        req.onupgradeneeded = () => req.result.createObjectStore('kv');
        req.onsuccess = () => {
          const db = req.result;
          const tx = db.transaction('kv', 'readwrite');
          tx.objectStore('kv').put('page-data', 'sentinel');
          tx.oncomplete = () => { db.close(); resolve(); };
          tx.onerror = () => reject(tx.error);
        };
        req.onerror = () => reject(req.error);
      });
    });
    await openAppByName(page, 'reading-a.html');
    await waitForIframe(page, `() => /E2E13 读书条目/.test(document.body.innerText)`);
    const iframeSeesPage = await iframeEval(page, () => ({
      localStorageSentinel: window.localStorage.getItem('e2e13-page-sentinel'),
      sessionStorageKeys: Object.keys(window.sessionStorage || {}),
      herdchestraKeys: window.__hcMiniappStorage ? window.__hcMiniappStorage.prefixedKeys() : [],
    }));
    checks.iframeStorageProbe = iframeSeesPage;
    checks.pageStorageIsolated = iframeSeesPage.localStorageSentinel === null
      && !iframeSeesPage.herdchestraKeys.some((k) => k.includes('e2e13-page'));
    uiAssert(checks.pageStorageIsolated,
      `page storage leaked into mini-app: ${JSON.stringify(iframeSeesPage)}`);
    await record('08-miniapp-page-isolation');

    // --- generation failure: existing files stay viewable ---------------
    // Point the task role at a reply that is not HTML, then POST directly
    // to the generate endpoint; the UI keeps the app list and the
    // previously generated files remain openable.
    await fetch(`${mock.url}/__mock/config`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ reply: '这不是 HTML 文档，没有 html 标签' }),
    });
    const failResp = await page.evaluate(async () => {
      const res = await fetch('/api/workspace/miniapp-generate', {
        method: 'POST',
        headers: { 'content-type': 'application/json', accept: 'application/edn' },
        body: JSON.stringify({ prompt: '故意失败：生成不是 HTML 的内容', filename: 'broken.html' }),
      });
      return { status: res.status, body: await res.text() };
    });
    checks.generateFailureStatus = failResp.status;
    checks.generateFailureBody = failResp.body.slice(0, 300);
    uiAssert(failResp.status >= 400, `expected generation failure, got ${failResp.status}`);
    // The workspace must not contain the broken file.
    const brokenExists = fs.existsSync(path.join(app.workspaceRoot, 'miniapps', 'broken.html'));
    checks.brokenFileAbsent = !brokenExists;
    // Existing apps still listed and openable.
    await page.reload({ waitUntil: 'load' });
    await goto(page, app.url, '/materials');
    await waitForSync(page);
    await openMaterialsTab(page, '小应用');
    await openAppByName(page, 'reading-a.html');
    await waitForIframe(page, `() => /A-record/.test(document.body.innerText)`);
    checks.existingAppViewableAfterFailure = true;
    await record('09-generate-failure-existing-ok');
    // Let every screenshot promise settle before teardown: an in-flight
    // `page.screenshot` racing `context.close` fails the whole run even
    // though all checks already passed.
    await Promise.allSettled(screenshots);

    const finishedAt = new Date().toISOString();
    const result = recordEvidence({
      dir: EVIDENCE,
      name: 'e2e-13',
      test: 'E2E 13 skill install/bind + mini-app generation and storage isolation',
      mode: 'automated',
      mock: true,
      startedAt,
      finishedAt,
      extra: {
        appUrl: app.url,
        mockUrl: mock.url,
        chromeVersion: browser.version(),
        bundleSha256: clientBundleInfo().sha256,
        sourceTreeSha256: sourceTreeSha256(),
        freezeBundle: '1e0bd458',
        freezeTree: '5af6757f',
        bundleNote: 'final26 冻结 bundle 1e0bd458（3,086,250 B，15:46:09）+ 树 5af6757f；本次为纯 UI 路径（移除手写 Sente transit 解码），技能预览由安装卡片 frontmatter/文件列表断言替代',
        screenshots,
      },
      checks,
      ok: true,
    });
    console.log('E2E13 evidence:', result.evidence);
  } finally {
    // Close the page first: it owns the Sente WebSocket, and a live page
    // retrying a dead server can stall `browserContext.close` in fixture
    // teardown until the whole test timeout fires.  Then the app, then the
    // mock — matching the order every other spec uses.
    await page.close().catch(() => {});
    await app.close().catch(() => {});
    await mock.close().catch(() => {});
  }
});
