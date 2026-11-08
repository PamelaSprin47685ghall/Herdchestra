// E2E 21: cost accounting口径 (reported / estimated / unknown).
//
// One turn exercises search + model + tool + continuation.  The dedicated
// mock model serves two models on one connection:
//   - `mock-priced`   reports usage on every request; the first request
//                     answers with a real `devops` tool call so the Owner
//                     runs a tool and the model continues.
//   - `mock-unknown`  never returns a usage block (the devops worker runs on
//                     the task role bound to this model), so one request
//                     stays "usage not provided" — never zero.
// Only `mock-priced` gets a stored price; `mock-unknown` stays unpriced.
// The spec then checks the message meta, the statistics page three口径,
// per-request dedup (model requests == mock calls), unknown-not-zero, and
// that changing the price keeps old price snapshots while new requests get
// the new currency.
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { test } from '@playwright/test';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockSearch } from './support/mock-search.mjs';
import {
  assert, assertEqual, assistantBodies, composer, goto, lastAssistantText,
  newSession, selectFirstSession, shot, turnLive, userTexts, waitForAssistant, waitForShell, waitForSync,
  writeJson, clientBundleInfo,
} from './support/ui.mjs';

const EVIDENCE = path.join(REPO_ROOT, 'plans/evidence/e2e-21');

const PRICED_USAGE_FIRST = { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 };
const PRICED_USAGE_LATER = { prompt_tokens: 50, completion_tokens: 10, total_tokens: 60 };

function sse(res, value) {
  res.write(`data: ${JSON.stringify(value)}\n\n`);
}

function chunk(id, model, delta, finishReason = null) {
  return {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }],
  };
}

function usageChunk(id, model, usage) {
  return {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [],
    usage: {
      ...usage,
      prompt_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
    },
  };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (c) => { body += c; });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

// Mock OpenAI-compatible service with per-model usage control.  `mock-priced`
// always reports usage (first request answers a `devops` tool call); any
// other model id answers plain text with NO usage field at all.
async function startUsageMock() {
  const state = { requests: [], priced: 0, unknown: 0 };
  const bodies = [];
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (req.method === 'GET' && url.pathname === '/v1/models') {
      const body = JSON.stringify({
        object: 'list',
        data: [
          { id: 'mock-priced', object: 'model', owned_by: 'e2e21-mock' },
          { id: 'mock-unknown', object: 'model', owned_by: 'e2e21-mock' },
        ],
      });
      res.writeHead(200, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
      return res.end(body);
    }
    if (req.method === 'GET' && url.pathname === '/__mock/state') {
      const body = JSON.stringify({ mock: true, ...state, requests: state.requests.length,
        bodies: bodies.map((b) => b.slice(0, 60000)) });
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(body);
    }
    if (req.method !== 'POST' || url.pathname !== '/v1/chat/completions') {
      res.writeHead(404, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: 'not found' }));
    }
    const raw = await readBody(req);
    let body = {};
    try { body = JSON.parse(raw || '{}'); } catch { /* empty */ }
    const model = String(body.model || 'mock-priced');
    const stream = body.stream === true;
    const priced = model.includes('priced');
    const id = `chatcmpl-e2e21-${state.requests.length}`;
    state.requests.push({
      at: new Date().toISOString(), model, stream,
      messageCount: Array.isArray(body.messages) ? body.messages.length : 0,
      hasTools: Array.isArray(body.tools) && body.tools.length > 0,
    });
    bodies.push(raw.slice(0, 200000));
    if (priced) state.priced += 1; else state.unknown += 1;
    const firstPriced = priced && state.priced === 1;
    const usage = firstPriced ? PRICED_USAGE_FIRST : PRICED_USAGE_LATER;
    const reply = priced
      ? `E2E21 priced reply #${state.priced}：费用口径验证文本。`
      : `E2E21 unknown-model reply：本响应不携带 usage。`;
    const devopsArgs = JSON.stringify({
      'verbose-intent': 'E2E21 费用口径探针：列出工作区事实',
      scope: {
        kind: 'reset-scope',
        'new-scope-goal': 'e2e21 cost accounting',
        'new-scope-retention-budget': 4,
      },
    });

    if (!stream) {
      const message = firstPriced
        ? { role: 'assistant', content: reply,
            tool_calls: [{ id: 'call_e2e21_devops', type: 'function',
                           function: { name: 'devops', arguments: devopsArgs } }] }
        : { role: 'assistant', content: reply };
      const payload = {
        id, object: 'chat.completion', created: Math.floor(Date.now() / 1000),
        model,
        choices: [{ index: 0, message, finish_reason: firstPriced ? 'tool_calls' : 'stop' }],
      };
      if (priced) payload.usage = { ...usage, prompt_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 } };
      const out = JSON.stringify(payload);
      res.writeHead(200, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(out) });
      return res.end(out);
    }

    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache', connection: 'keep-alive',
      'x-herdchestra-mock': 'e2e21-usage',
    });
    res.flushHeaders?.();
    if (firstPriced) {
      // The first turn is the only non-Nudge model call: whatever text it
      // carries is the user-visible reply.  A tool-call-only first round
      // leaves every later continuation hidden as a Nudge answer, so the
      // turn would settle with no visible reply at all.  Real providers
      // allow content and tool_calls in one response; stream the preamble
      // first, then the devops call.
      for (let i = 0; i < reply.length; i += 12) {
        sse(res, chunk(id, model, { content: reply.slice(i, i + 12) }));
      }
      sse(res, chunk(id, model, { role: 'assistant', content: '' }));
      sse(res, chunk(id, model, {
        tool_calls: [{
          index: 0, id: 'call_e2e21_devops', type: 'function',
          function: { name: 'devops', arguments: devopsArgs },
        }],
      }));
      sse(res, chunk(id, model, {}, 'tool_calls'));
      if (priced) sse(res, usageChunk(id, model, usage));
      res.write('data: [DONE]\n\n');
      return res.end();
    }
    sse(res, chunk(id, model, { role: 'assistant', content: '' }));
    for (let i = 0; i < reply.length; i += 12) {
      sse(res, chunk(id, model, { content: reply.slice(i, i + 12) }));
    }
    sse(res, chunk(id, model, {}, 'stop'));
    if (priced) sse(res, usageChunk(id, model, usage));
    res.write('data: [DONE]\n\n');
    return res.end();
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const { port } = server.address();
  return {
    port,
    url: `http://127.0.0.1:${port}`,
    state,
    async close() { await new Promise((r) => server.close(r)); },
  };
}

async function mockState(mock) {
  return (await fetch(`${mock.url}/__mock/state`)).json();
}

async function gotoSettingsData(page, appUrl) {
  await page.goto(`${appUrl}/#/settings`, { waitUntil: 'load' });
  await page.locator('.hc-settings').first().waitFor({ state: 'visible', timeout: 30000 });
  await page.locator('.hc-sync').filter({ hasText: '已同步' }).first()
    .waitFor({ state: 'visible', timeout: 30000 });
  await page.getByRole('button', { name: '数据中心', exact: true }).first().click();
  await page.locator('.hc-diagnostics').first().waitFor({ state: 'visible', timeout: 30000 });
}

async function refreshStats(page, waitText = null) {
  const btn = page.getByRole('button', { name: '刷新统计', exact: true }).first();
  if (await btn.count()) await btn.click().catch(() => {});
  if (waitText) {
    // The stats view is cached; wait until the refreshed copy actually
    // carries the expected marker instead of returning on stale text.
    await page.waitForFunction((needle) => document.body.innerText.includes(needle),
      waitText, { timeout: 30000 });
    return;
  }
  await page.waitForFunction(() => {
    const text = document.body.innerText;
    return text.includes('请求用量来源') || text.includes('统计读取失败')
      || text.includes('当前范围没有可计入');
  }, null, { timeout: 30000 });
}

async function bodyText(page) {
  return page.locator('body').innerText();
}

function pricePanel(page) {
  return page.locator('section.hc-diag-panel').filter({ hasText: '模型价格' }).first();
}

async function setPriceField(page, label, value) {
  const field = pricePanel(page).locator('.hc-diag-field', { hasText: label }).first();
  const input = field.locator('input').first();
  await input.fill(String(value));
}

async function selectPriceOption(page, label, optionValue) {
  const field = pricePanel(page).locator('.hc-diag-field', { hasText: label }).first();
  const select = field.locator('select').first();
  await select.selectOption({ value: optionValue });
}

async function savePrice(page) {
  await pricePanel(page).getByRole('button', { name: '保存价格', exact: true }).first().click();
  await page.getByText('价格已保存', { exact: false }).first()
    .waitFor({ state: 'visible', timeout: 30000 });
}

// Wait until the turn is genuinely finished: the visible assistant reply has
// committed, no generation/unattended indicator is live, and the mock has
// not seen a new model request for a quiet window.  `ensureIdle` is the
// wrong tool here — it clicks the stop button whenever the turn looks live,
// which cancels the unattended Nudge tail mid-flight.
async function waitTurnSettled(page, mock, timeout = 120000) {
  const started = Date.now();
  let quietSince = null;
  let lastMockCount = -1;
  let stablePolls = 0;
  while (Date.now() - started < timeout) {
    const unattended = await page.getByText('无人值守').count().catch(() => 0);
    const live = (await turnLive(page)) || unattended > 0;
    let mockQuiet = true;
    if (mock) {
      const st = await mockState(mock).catch(() => null);
      const n = st ? st.requests : -1;
      if (n !== lastMockCount) {
        lastMockCount = n;
        stablePolls = 0;
      } else {
        stablePolls += 1;
      }
      mockQuiet = stablePolls >= 3;
    }
    if (!live && mockQuiet) {
      if (quietSince === null) quietSince = Date.now();
      if (Date.now() - quietSince >= 2500) return true;
    } else {
      quietSince = null;
    }
    await page.waitForTimeout(500);
  }
  return false;
}

async function sendTurn(page, text, mock = null) {
  // The Sente channel can briefly report 中断 right after UI-only changes;
  // sending is gated on 已同步, so wait for the badge before touching the
  // composer and once more if the first check still sees a disabled send.
  await waitForSync(page, 30000);
  const assistantBefore = await assistantBodies(page).count();
  await composer(page).fill(text);
  const sendButton = page.locator('button[aria-label="发送消息"]').first();
  await sendButton.waitFor({ state: 'visible', timeout: 15000 });
  if (await sendButton.isDisabled()) {
    await waitForSync(page, 30000).catch(() => {});
    await page.waitForTimeout(1000);
    if (!(await sendButton.isDisabled())) {
      await sendButton.click({ timeout: 15000 });
      await waitTurnSettled(page, mock);
      await page.waitForTimeout(1000);
      return;
    }
    const dump = await page.locator('body').innerText().catch(() => '');
    throw new Error(`send button disabled; page text:\n${dump.slice(0, 3000)}`);
  }
  await sendButton.click({ timeout: 15000 });
  // First wait for THIS turn's visible reply: the first model call is the
  // only non-Nudge call, so its committed text is what the operator sees.
  // Polling the body count avoids the pre-stream idle gap that made
  // `ensureIdle` return before the turn even started.
  const replyDeadline = Date.now() + 90000;
  while (Date.now() < replyDeadline) {
    if ((await assistantBodies(page).count()) > assistantBefore) break;
    await page.waitForTimeout(250);
  }
  const settled = await waitTurnSettled(page, mock);
  if (!settled) {
    await shot(page, EVIDENCE, 'e2e21-sendTurn-not-settled').catch(() => {});
  }
  await page.waitForTimeout(1000);
}

async function enableRequiredSearch(page) {
  const group = page.locator('.hc-search-controls[aria-label="联网搜索"]').first();
  await group.waitFor({ state: 'visible', timeout: 20000 });
  const toggle = group.locator('button[aria-expanded]').first();
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') {
    await toggle.click({ timeout: 10000 });
  }
  const serviceSelect = page.locator('.hc-search-row select').first();
  await serviceSelect.waitFor({ state: 'visible', timeout: 15000 });
  const options = await serviceSelect.locator('option').evaluateAll(
    (nodes) => nodes.map((n) => ({ value: n.value, label: n.textContent })));
  const usable = options.find((o) => o.value);
  assert(usable, `no selectable search service: ${JSON.stringify(options)}`);
  await serviceSelect.selectOption({ value: usable.value });
  await page.locator('.hc-search-mode', { hasText: '本轮必须搜索' }).first()
    .click({ timeout: 10000 });
}

test.describe.configure({ mode: 'serial' });

test('@core E2E 21 cost accounting口径', async ({ page, browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE, { recursive: true });
  const mock = await startUsageMock();
  const search = await startMockSearch();
  const app = await startApp({
    evidenceDir: EVIDENCE,
    keepTemp: true,
    // The mock never advances the Plan ledger, so every turn ends in the
    // unattended Nudge tail.  Two rounds still prove the continuation path;
    // the default budget of eight only lengthens each turn.
    nudgeRoundBudget: 2,
    models: {
      connections: [{
        id: 'mock',
        name: 'E2E21 Mock',
        baseUrl: `${mock.url}/v1`,
        models: [
          { id: 'mock-priced', displayName: 'Mock Priced', maxOutputTokens: 2048, capabilities: ['tools'] },
          { id: 'mock-unknown', displayName: 'Mock Unknown', maxOutputTokens: 2048, capabilities: ['tools'] },
        ],
      }],
      roles: {
        main: { connection: 'mock', model: 'mock-priced' },
        task: { connection: 'mock', model: 'mock-unknown' },
      },
    },
    search: {
      services: [{
        id: 'mock-searxng', name: 'Mock SearXNG', kind: ':searxng',
        baseUrl: search.searxngUrl, enabled: true,
      }],
      defaultServiceId: 'mock-searxng',
    },
  });
  const marker = `E2E21-${Date.now()}`;
  const result = {
    test: 'E2E 21', mode: 'automated', mock: true,
    startedAt: new Date().toISOString(), marker,
    appUrl: app.url, mockUrl: mock.url, searchUrl: search.url,
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: (() => {
      try {
        const tree = execSync('git rev-parse HEAD^{tree}', { cwd: REPO_ROOT }).toString().trim();
        const spec = fs.readFileSync(new URL(import.meta.url));
        return crypto.createHash('sha256')
          .update(`${tree}\n`)
          .update(spec)
          .digest('hex');
      } catch { return null; }
    })(),
    checks: {}, screenshots: [],
  };
  const evidence = (name, value) => writeJson(EVIDENCE, name, value);
  try {
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await enableRequiredSearch(page);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e21-01-search-required'));

    // Turn 1: search + priced model tool call + devops worker (unknown model,
    // no usage) + priced continuation.  No price stored yet.
    await sendTurn(page, `E2E21 第一轮 ${marker}：搜索并调用工具后续答`, mock);
    result.turn1Text = (await lastAssistantText(page)).slice(0, 200);
    result.checks.turn1Replied = result.turn1Text.includes('E2E21 priced reply');
    assert(result.checks.turn1Replied, `turn1 reply missing: ${result.turn1Text}`);

    const mockAfterTurn1 = await mockState(mock);
    result.mockAfterTurn1 = mockAfterTurn1;
    result.checks.searchUsed = (mockAfterTurn1.requests || 0) >= 1;
    result.checks.toolRoundTrip = mockAfterTurn1.priced >= 2 && mockAfterTurn1.unknown >= 1;
    assert(result.checks.toolRoundTrip,
      `expected priced>=2 and unknown>=1 requests, got ${JSON.stringify(mockAfterTurn1)}`);

    // Message detail: the finished assistant message carries reported usage.
    const metas = await page.locator('.hc-message-meta').allInnerTexts();
    result.messageMetas = metas;
    const usageMeta = metas.find((t) => t.includes('用量'));
    result.checks.messageUsageLabel = Boolean(usageMeta)
      && /用量\s+\d+\s*\/\s*\d+/.test(usageMeta || '')
      && !/用量\s+0\s*\/\s*0/.test(usageMeta || '');
    assert(result.checks.messageUsageLabel,
      `message meta lacks a real usage label: ${JSON.stringify(metas)}`);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e21-02-turn1-done'));

    // Statistics page before any price: reported > 0, unknown > 0, no estimate.
    await gotoSettingsData(page, app.url);
    await refreshStats(page);
    let text = await bodyText(page);
    result.beforePriceText = text.slice(0, 12000);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e21-03-before-price'));

    const sourceLine = text.match(/请求用量来源：服务返回\s*(\d+)；估算\s*(\d+)；未提供\s*(\d+)/);
    result.checks.hasSourceCounts = Boolean(sourceLine);
    assert(sourceLine, 'usage source counts line missing');
    result.reportedBefore = Number(sourceLine[1]);
    result.estimatedBefore = Number(sourceLine[2]);
    result.unknownBefore = Number(sourceLine[3]);
    // priced requests = 2, worker request = 1 -> reported 2, unknown 1 (or more
    // if the adapter splits attempts; accept >= and keep exact mock count check).
    result.checks.reportedNonZero = result.reportedBefore >= 2;
    result.checks.unknownNonZero = result.unknownBefore >= 1;
    result.checks.noEstimateBefore = result.estimatedBefore === 0;
    result.checks.unknownNotZeroText = text.includes('不按 0 计算')
      || text.includes('未提供') || text.includes('未知');
    assert(result.checks.reportedNonZero, `reported=${result.reportedBefore}`);
    assert(result.checks.unknownNonZero, `unknown=${result.unknownBefore}`);
    assert(result.checks.noEstimateBefore, `estimated=${result.estimatedBefore}`);

    // Save a USD price for the priced model only.
    const panel = pricePanel(page);
    await panel.locator('.hc-diag-field', { hasText: '服务' }).locator('select').first()
      .selectOption({ index: 1 });
    // The model select is filtered by the chosen service; pick the priced row.
    const modelSelect = panel.locator('.hc-diag-field', { hasText: '模型' }).locator('select').first();
    const modelOptions = await modelSelect.locator('option').evaluateAll(
      (nodes) => nodes.map((n) => ({ value: n.value, label: n.textContent })));
    result.priceModelOptions = modelOptions;
    const pricedOption = modelOptions.find((o) => o.value && o.value.includes('Priced'));
    const unknownOption = modelOptions.find((o) => o.value && o.value.includes('Unknown'));
    assert(pricedOption, `no priced model option: ${JSON.stringify(modelOptions)}`);
    await modelSelect.selectOption({ value: pricedOption.value });
    await setPriceField(page, '币种', 'USD');
    await setPriceField(page, '输入 / 百万 token', '1');
    await setPriceField(page, '输出 / 百万 token', '2');
    await setPriceField(page, '缓存命中 / 百万 token', '0.5');
    await setPriceField(page, '缓存写入 / 百万 token', '0');
    await setPriceField(page, '价格来源', 'E2E21 spec');
    await setPriceField(page, '来源日期', '2026-11-08');
    await savePrice(page);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e21-04-price-usd'));
    await refreshStats(page, '币种 USD');
    text = await bodyText(page);
    result.afterUsdText = text.slice(0, 12000);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e21-05-usd-stats'));

    result.checks.usdEstimated = /币种 USD/.test(text) && /USD 0\.0/.test(text);
    result.checks.unknownModelUnestimated = /Mock Unknown|未知模型/.test(text)
      && /未估算/.test(text);
    result.checks.coverageLine = /估算覆盖\s*\d+\s*\/\s*\d+/.test(text);
    assert(result.checks.usdEstimated, 'USD estimate missing after price save');
    assert(result.checks.unknownModelUnestimated, 'unknown-price model not shown as unestimated');
    assert(result.checks.coverageLine, 'estimate coverage line missing');

    // Turn 2: new priced request snapshots the USD price.
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await sendTurn(page, `E2E21 第二轮 ${marker}：USD 价格已保存`, mock);

    // Turn 3 after switching the price to EUR: new requests snapshot EUR,
    // the USD-snapshotted request keeps USD.
    await gotoSettingsData(page, app.url);
    await setPriceField(page, '币种', 'EUR');
    await setPriceField(page, '输入 / 百万 token', '2');
    await setPriceField(page, '输出 / 百万 token', '4');
    await savePrice(page);
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await sendTurn(page, `E2E21 第三轮 ${marker}：EUR 新价格`, mock);

    await gotoSettingsData(page, app.url);
    await refreshStats(page, '币种 EUR');
    text = await bodyText(page);
    result.finalText = text.slice(0, 16000);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e21-06-final-stats'));

    result.checks.usdAndEurSeparated = /币种 USD/.test(text) && /币种 EUR/.test(text);
    result.checks.noCrossCurrencyTotal = !/合计|总计/.test(
      (text.match(/币种[\s\S]{0,400}/) || [''])[0]);
    result.checks.priceNote = text.includes('旧记录保留生成时的价格与币种')
      || text.includes('修改价格默认影响未来请求');
    assert(result.checks.usdAndEurSeparated, 'USD and EUR currency groups not separated');
    assert(result.checks.priceNote, 'price-change history note missing');

    // Multi-request turn is counted once per request, never double-counted:
    // statistics model-request count must equal the number of real mock calls.
    const countsLine = text.match(/模型请求次数[\s\S]{0,80}?(\d+)/);
    const mockFinal = await mockState(mock);
    result.mockFinal = mockFinal;
    result.mockTotalRequests = mockFinal.requests;
    // The stats page may scope to the default 30-day range which covers all
    // sends here; compare against every recorded mock call.
    result.checks.requestCountMatchesMock = countsLine
      ? Number(countsLine[1]) === mockFinal.requests
      : false;
    result.countsCardText = countsLine ? countsLine[0].slice(0, 120) : null;
    assert(result.checks.requestCountMatchesMock,
      `model request count ${countsLine && countsLine[1]} != mock calls ${mockFinal.requests}`);

    // Expand every turn and verify per-request provenance labels.
    const turns = page.locator('.hc-diag-turn');
    const turnCount = await turns.count();
    result.turnRows = [];
    for (let i = 0; i < turnCount; i += 1) {
      const turn = turns.nth(i);
      const summaryText = await turn.locator('summary').innerText();
      await turn.locator('summary').click();
      await page.waitForTimeout(200);
      const requests = turn.locator('.hc-diag-request');
      const rows = [];
      for (let j = 0; j < await requests.count(); j += 1) {
        rows.push(await requests.nth(j).innerText());
      }
      result.turnRows.push({ summary: summaryText.slice(0, 200), requests: rows.map((r) => r.slice(0, 500)) });
    }
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e21-07-turn-details'));

    const allRequestText = result.turnRows.flatMap((t) => t.requests).join('\n');
    result.checks.requestProvenanceLabels = /服务返回|估算|未提供|未估算/.test(allRequestText);
    result.checks.perRequestCurrencies = /USD|EUR/.test(allRequestText);
    // The USD-snapshotted turn-2 request must still say USD; EUR requests say EUR.
    const usdRows = result.turnRows.flatMap((t) => t.requests).filter((r) => r.includes('USD'));
    const eurRows = result.turnRows.flatMap((t) => t.requests).filter((r) => r.includes('EUR'));
    result.usdRequestRows = usdRows.length;
    result.eurRequestRows = eurRows.length;
    result.checks.oldPriceSnapshotKept = usdRows.length >= 1 && eurRows.length >= 1;
    assert(result.checks.requestProvenanceLabels, 'request rows lack provenance labels');
    assert(result.checks.oldPriceSnapshotKept,
      `expected both USD and EUR request rows, got usd=${usdRows.length} eur=${eurRows.length}`);

    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    // /sessions lands on the session list; open the conversation before
    // reading user message bodies.
    await selectFirstSession(page).catch(() => {});
    await page.waitForTimeout(500);
    const users = await userTexts(page).catch(() => []);
    result.userTexts = users.slice(0, 10);
    result.checks.sendsRecorded = users.filter((u) => u.includes(marker)).length === 3;

    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    evidence('e2e21-result', result);
    assert(result.ok, 'one or more E2E 21 checks failed');
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockFinal = await mockState(mock).catch(() => null);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e21-error').catch(() => null));
    evidence('e2e21-result', result);
    throw error;
  } finally {
    await app.close();
    await mock.close();
    await search.close();
  }
});
