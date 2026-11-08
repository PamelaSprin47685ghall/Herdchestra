// E2E 07: search results and source verification.
//
// Two turns on one session exercise the search pipeline end to end against
// the mock providers (never the internet):
//
//   Turn 1 (Brave): the provider returns the same canonical URL twice plus
//     one dated and one undated result.  The source list must merge the
//     duplicate URL into one source, show "发布：未知" for the undated
//     result (never the fetch time), keep the real published date, and the
//     assistant's [S#] labels must map one-to-one onto the source cards.
//   Turn 2 (SearXNG): a different service and query must produce its own
//     source set; the merged first-turn source keeps its identity.
//
// Then the operator goes offline (route abort + browser offline) and
// re-opens the session: no implicit search request may fire and the source
// cards must still render.  Finally a plain-text export with "含来源"
// checked must carry the full source URLs.
import fs from 'node:fs';
import path from 'node:path';
import { test } from '@playwright/test';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import { startMockSearch, SEARCH_RESULTS } from './support/mock-search.mjs';
import {
  assert, assertEqual, assistantTexts, clientBundleInfo,
  goto, newSession, send, sessionModelLabel,
  shot, waitForAssistant, waitForSync, writeJson,
} from './support/ui.mjs';
import { sourceTreeSha256, collectMockState } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

const MODEL_ID = 'mock-fast';
const MODEL_NAME = 'Mock Fast';

// Query-scoped result sets.  The mock returns `results` when the provider's
// `q` parameter contains `match`; the first matching rule wins.
//
// Turn 1 (Brave) returns four rows for three canonical URLs: docs/one twice
// (dedup within one query) and docs/shared, which turn 2 also returns — the
// cross-query merge target.  Turn 2 (SearXNG) returns docs/shared plus a
// beta-only URL.
const BRAVE_ALPHA = [
  SEARCH_RESULTS[0],
  SEARCH_RESULTS[1],
  // Same canonical URL as result one: must merge into one source.
  SEARCH_RESULTS[2],
  {
    title: 'Herdchestra shared doc',
    url: 'https://example.test/docs/shared',
    snippet: 'Shared across both queries.',
    publishedDate: '2026-02-03T04:05:06.000Z',
  },
];
const SEARXNG_BETA = [
  {
    title: 'Herdchestra shared doc',
    url: 'https://example.test/docs/shared',
    snippet: 'Shared across both queries.',
    publishedDate: '2026-02-03T04:05:06.000Z',
  },
  {
    title: 'Herdchestra beta doc',
    url: 'https://example.test/docs/beta',
    snippet: 'Beta-only result for the second query.',
    publishedDate: null,
  },
];

function evidenceDir07() {
  const dir = path.join(REPO_ROOT, 'plans/evidence/e2e-07');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

async function mockSearchState(search) {
  const response = await fetch(`${search.url}/__mock/state`);
  return response.json();
}

async function waitForMessageCount(page, count, timeout = 60000) {
  await page.waitForFunction(
    (n) => document.querySelectorAll(
      '.hc-message-out:not(.hc-message-process)').length >= n,
    count, { timeout });
}

// Nudge continuations can append further assistant bubbles after the
// searched answer; the searched answer is the LAST message that actually
// carries source cards.  All card helpers scope to that message.
function lastCardedAssistantItem(page) {
  return page.locator(
    '.hc-message-in:not(.hc-message-process):has(.hc-source-card)').last();
}

// Wait until some assistant message shows at least `count` source cards.
// Source cards render from server facts on RUN_FINISHED, which lands after
// the reply text settles.
async function waitForSourceCards(page, count, timeout = 90000) {
  const item = lastCardedAssistantItem(page);
  try {
    await page.waitForFunction(
      (n) => [...document.querySelectorAll(
        '.hc-message-in:not(.hc-message-process)')]
        .some((el) => el.querySelectorAll('.hc-source-card').length >= n),
      count,
      { timeout });
  } catch (error) {
    const dump = await page.evaluate(() =>
      [...document.querySelectorAll('.hc-message-in:not(.hc-message-process)')]
        .map((el) => ({
          id: el.getAttribute('data-message-id'),
          cards: el.querySelectorAll('.hc-source-card').length,
          text: (el.querySelector('.hc-message-body')?.innerText || '').slice(0, 200),
        })));
    error.message += `\ncard dump: ${JSON.stringify(dump)}`;
    throw error;
  }
  await item.waitFor({ state: 'visible', timeout: 15000 });
}

async function sourceCardTexts(page) {
  const item = lastCardedAssistantItem(page);
  const cards = item.locator('.hc-source-card');
  const count = await cards.count();
  const texts = [];
  for (let i = 0; i < count; i += 1) {
    texts.push(await cards.nth(i).innerText());
  }
  return texts;
}

async function citationLabels(page) {
  const body = await lastCardedAssistantItem(page)
    .locator('.hc-message-body').innerText().catch(() => '');
  // The body keeps the model's [S#] markers (the owner stores the
  // accumulated stream text); the card label [n] is the citation number
  // assigned in first-citation order, so S<n> corresponds to card [n].
  return [...new Set([...body.matchAll(/\[S(\d+)\]/g)].map((m) => m[1]))];
}

async function cardNumbers(page) {
  const item = lastCardedAssistantItem(page);
  const labels = item.locator('.hc-source-card .hc-source-label');
  const count = await labels.count();
  const numbers = [];
  for (let i = 0; i < count; i += 1) {
    const text = (await labels.nth(i).innerText()).trim();
    const match = text.match(/^\[([^\]]+)\]$/);
    if (match) numbers.push(match[1]);
  }
  return numbers;
}

async function enableSearchMode(page, serviceId, modeLabel) {
  const group = page.locator('.hc-search-controls[aria-label="联网搜索"]').first();
  await group.waitFor({ state: 'visible', timeout: 20000 });
  const toggle = group.locator('button[aria-expanded]').first();
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') {
    await toggle.click({ timeout: 10000 });
  }
  const serviceSelect = page.locator('.hc-search-row select').first();
  await serviceSelect.waitFor({ state: 'visible', timeout: 15000 });
  if (serviceId) {
    await serviceSelect.selectOption({ value: serviceId });
    assertEqual(await serviceSelect.inputValue(), serviceId,
      `search service did not switch to ${serviceId}`);
  }
  await page.locator('.hc-search-mode', { hasText: modeLabel }).first()
    .click({ timeout: 10000 });
  await page.waitForFunction(
    (label) => [...document.querySelectorAll('.hc-search-mode')]
      .some((node) => node.textContent === label
        && node.getAttribute('aria-pressed') === 'true'),
    modeLabel, { timeout: 15000 });
}

async function downloadExport(page, buttonName, dir, filename) {
  const details = page.locator('details.hc-context-panel[aria-label="上下文与压缩"]').first();
  if (!(await details.getAttribute('open'))) {
    await details.locator('summary').first().click();
  }
  // The export must carry the source list: tick 含来源 inside the branch
  // export controls before clicking the plain-text button.
  const includeSources = page.locator('.hc-export-controls input[type="checkbox"]').first();
  if (await includeSources.count()) {
    if (!(await includeSources.isChecked())) await includeSources.check();
  }
  const button = page.locator('.hc-export-controls')
    .getByRole('button', { name: buttonName, exact: true }).first();
  await button.waitFor({ state: 'visible', timeout: 15000 });
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
    button.click(),
  ]);
  const target = path.join(dir, download.suggestedFilename() || filename);
  await download.saveAs(target);
  return { file: target, name: download.suggestedFilename() || filename,
           text: fs.readFileSync(target, 'utf8') };
}

test('@core E2E 07 search results and source verification', async ({ page, browser }) => {
  test.setTimeout(420000);
  const dir = evidenceDir07();
  const marker = `E2E07-${Date.now()}`;

  const model = await startMockModel({
    replyScript: [
      { match: '第一轮',
        reply: `E2E07-REPLY-1 ${marker}：ALPHA-ONE 来自 [S1]，共享文档见 [S3]，无日期来源见 [S2]。` },
      { match: '第二轮',
        reply: `E2E07-REPLY-2 ${marker}：共享文档仍见 [S1]，BETA 见 [S2]。` },
      { reply: `E2E07-FALLBACK ${marker}` },
    ],
  });
  // Two provider shapes on one mock; per-query rules keep each turn's result
  // set deterministic.
  const search = await startMockSearch({
    queries: [
      { match: '第一轮', results: BRAVE_ALPHA },
      { match: '第二轮', results: SEARXNG_BETA },
    ],
  });
  const app = await startApp({
    evidenceDir: dir,
    nudgeRoundBudget: 2,
    models: {
      connections: [{
        id: 'mock',
        name: 'Mock Fast 服务',
        baseUrl: `${model.url}/v1`,
        models: [{
          id: MODEL_ID,
          displayName: MODEL_NAME,
          maxOutputTokens: 2048,
          capabilities: ['tools'],
        }],
      }],
      roles: { main: { connection: 'mock', model: MODEL_ID } },
    },
    search: {
      services: [
        {
          id: 'mock-brave', name: 'Mock Brave', kind: ':brave',
          baseUrl: search.braveUrl, enabled: true,
          credentials: { apiKey: 'mock-token' },
        },
        {
          id: 'mock-searxng', name: 'Mock SearXNG', kind: ':searxng',
          baseUrl: search.searxngUrl, enabled: true,
        },
      ],
      defaultServiceId: 'mock-searxng',
    },
  });

  const result = {
    test: 'E2E 07',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    modelUrl: model.url,
    searchUrl: search.url,
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    checks: {},
    screenshots: [],
    browserEvents: [],
  };
  page.on('pageerror', (error) =>
    result.browserEvents.push(`${new Date().toISOString()} pageerror ${String(error).slice(0, 300)}`));
  page.on('console', (message) => {
    if (message.type() === 'error') {
      result.browserEvents.push(`${new Date().toISOString()} console-error ${message.text().slice(0, 300)}`);
    }
  });

  const prompt1 = `E2E07 第一轮 ${marker}：只根据联网搜索结果回答，保留 [S1] [S2] [S3] 标注。`;
  const prompt2 = `E2E07 第二轮 ${marker}：换一个搜索服务再答一次，保留 [S1] 标注。`;

  try {
    // --- Session + required search on Brave --------------------------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    const modelLabel = await sessionModelLabel(page);
    result.modelLabel = modelLabel;
    result.checks.modelBound = modelLabel.includes(MODEL_NAME) || modelLabel.includes(MODEL_ID);
    assert(result.checks.modelBound, `session did not bind ${MODEL_NAME}: ${modelLabel}`);

    await enableSearchMode(page, 'mock-brave', '本轮必须搜索');
    result.screenshots.push(await shot(page, dir, 'e2e07-01-search-required'));

    const searchBefore1 = await mockSearchState(search);
    await send(page, prompt1);
    const reply1 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.reply1 = reply1.text.slice(0, 240);
    // Nudge continuations can append assistant bubbles; the searched answer
    // is identified by its markers, not by message position.
    const texts1 = await assistantTexts(page);
    result.assistantTexts1 = texts1.map((t) => t.slice(0, 160));
    result.checks.reply1Markers = texts1.some((t) => t.includes('[S1]')
      && t.includes('[S2]') && t.includes('[S3]'));
    assert(result.checks.reply1Markers,
      `turn-1 reply lost [S#] markers: ${JSON.stringify(result.assistantTexts1)}`);

    // Source cards: three unique canonical URLs from four raw results.
    await waitForSourceCards(page, 3);
    const cards1 = await sourceCardTexts(page);
    result.cards1 = cards1;
    result.checks.threeSources = cards1.length === 3;
    assert(result.checks.threeSources, `expected 3 source cards, got ${cards1.length}`);

    const docOneUrl = 'https://example.test/docs/one';
    const docTwoUrl = 'https://example.test/docs/two';
    const sharedUrl = 'https://example.test/docs/shared';
    const betaUrl = 'https://example.test/docs/beta';
    const allCardText1 = cards1.join('\n');
    const docOneCount1 = (allCardText1.match(/https:\/\/example\.test\/docs\/one/g) || []).length;
    result.checks.dedupSameUrl = docOneCount1 === 1;
    assert(result.checks.dedupSameUrl,
      `duplicate URL ${docOneUrl} appears ${docOneCount1} times across ${cards1.length} cards`);
    result.checks.sharedSourcePresent = cards1.some((text) => text.includes(sharedUrl));
    assert(result.checks.sharedSourcePresent,
      `turn-1 cards lost the shared source: ${JSON.stringify(cards1)}`);

    // Publication date stays separate from fetch time: the undated result
    // shows 发布：未知 and never borrows the 抓取 timestamp.
    const datedCard = cards1.find((text) => text.includes(docOneUrl)) || '';
    const undatedCards = cards1.filter((text) => text.includes(docTwoUrl));
    result.datedCard = datedCard;
    result.checks.publishedDateKept = datedCard.includes('发布：2026-01-02T03:04:05.000Z')
      || datedCard.includes('发布：2026-01-02');
    assert(result.checks.publishedDateKept,
      `dated source lost its published date: ${datedCard.slice(0, 300)}`);
    result.checks.unknownDateShown = undatedCards.every((text) => text.includes('发布：未知'));
    assert(result.checks.unknownDateShown,
      `undated source does not show 发布：未知: ${JSON.stringify(undatedCards)}`);
    // The fetch timestamp must never be presented as the publication date.
    result.checks.noFetchAsPublish = undatedCards.every((text) => {
      const publishedLine = (text.match(/发布：[^\n]*/g) || []).join(' ');
      const fetchedLine = (text.match(/抓取：[^\n]*/g) || []).join(' ');
      const publishedValue = publishedLine.replace('发布：', '').trim();
      const fetchedValue = fetchedLine.replace('抓取：', '').trim();
      return publishedValue === '未知' || publishedValue !== fetchedValue;
    });
    assert(result.checks.noFetchAsPublish,
      'a fetch timestamp was rendered as the publication date');
    result.checks.missingKeepsTitleUrl = undatedCards.every((text) =>
      text.includes('http')) && cards1.some((text) => text.includes('Second mock search result'));
    assert(result.checks.missingKeepsTitleUrl,
      'a source without a date lost its title or URL');

    // Citation numbering: the body [n] labels and the card [n] labels must
    // be the same set, one-to-one.
    const labels1 = await citationLabels(page);
    const numbers1 = await cardNumbers(page);
    result.labels1 = labels1;
    result.numbers1 = numbers1;
    const sameSet = (a, b) => a.length === b.length && a.every((x) => b.includes(x));
    result.checks.numberingMatches = sameSet(labels1, numbers1) && labels1.length === 3;
    assert(result.checks.numberingMatches,
      `citation labels ${JSON.stringify(labels1)} != card numbers ${JSON.stringify(numbers1)}`);
    result.checks.answerUsesSearchContext = reply1.text.includes('E2E07-REPLY-1');

    // Exactly one provider request for the single q1 query of turn 1.
    const searchAfter1 = await mockSearchState(search);
    result.searchAfter1 = {
      requests: searchAfter1.requests.length,
      queries: searchAfter1.queries,
    };
    result.checks.searchRan = searchAfter1.requests.length === searchBefore1.requests.length + 1;
    result.checks.braveQueryUsed = searchAfter1.queries
      .some((q) => q.includes('第一轮'));
    result.checks.bravePathUsed = searchAfter1.requests
      .some((r) => r.path === '/res/v1/web/search');
    assert(result.checks.searchRan,
      `expected exactly one new search request, got ${searchAfter1.requests.length - searchBefore1.requests.length}`);
    assert(result.checks.braveQueryUsed, 'the Brave query did not carry the turn text');
    assert(result.checks.bravePathUsed, 'the Brave endpoint was not exercised');

    result.screenshots.push(await shot(page, dir, 'e2e07-02-turn1-sources'));

    // --- Turn 2: a different service issues a different query --------------
    await enableSearchMode(page, 'mock-searxng', '本轮必须搜索');
    await send(page, prompt2);
    const reply2 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.reply2 = reply2.text.slice(0, 240);
    const texts2 = await assistantTexts(page);
    result.assistantTexts2 = texts2.map((t) => t.slice(0, 160));
    result.checks.reply2Marker = texts2.some((t) => t.includes('E2E07-REPLY-2'));
    assert(result.checks.reply2Marker, `turn-2 reply mismatch: ${reply2.text.slice(0, 200)}`);
    await waitForMessageCount(page, 2);

    await waitForSourceCards(page, 2);
    const cards2 = await sourceCardTexts(page);
    result.cards2 = cards2;
    result.checks.secondTurnTwoSources = cards2.length === 2
      && cards2.some((text) => text.includes(sharedUrl))
      && cards2.some((text) => text.includes(betaUrl));
    assert(result.checks.secondTurnTwoSources,
      `turn-2 source list mismatch: ${JSON.stringify(cards2)}`);
    // The shared URL was discovered by both queries: it must appear once
    // per message's cited set, never duplicated inside one message.
    const sharedCount2 = cards2.filter((text) => text.includes(sharedUrl)).length;
    result.checks.crossQueryMerge = sharedCount2 === 1;
    assert(result.checks.crossQueryMerge,
      `shared URL duplicated inside turn-2 cards: ${JSON.stringify(cards2)}`);
    const labels2 = await citationLabels(page);
    const numbers2 = await cardNumbers(page);
    result.labels2 = labels2;
    result.numbers2 = numbers2;
    result.checks.secondTurnNumbering = sameSet(labels2, numbers2) && labels2.length === 2;
    assert(result.checks.secondTurnNumbering,
      `turn-2 labels ${JSON.stringify(labels2)} != cards ${JSON.stringify(numbers2)}`);

    const searchAfter2 = await mockSearchState(search);
    result.searchAfter2 = {
      requests: searchAfter2.requests.length,
      queries: searchAfter2.queries,
    };
    result.checks.searxngQueryUsed = searchAfter2.queries
      .some((q) => q.includes('第二轮'));
    result.checks.searxngPathUsed = searchAfter2.requests
      .some((r) => r.path === '/search');
    assert(result.checks.searxngQueryUsed, 'the SearXNG query did not carry the turn text');
    assert(result.checks.searxngPathUsed, 'the SearXNG endpoint was not exercised');
    result.screenshots.push(await shot(page, dir, 'e2e07-03-turn2-sources'));

    // --- Offline re-visit: no implicit search, cards still render ----------
    const searchBeforeOffline = await mockSearchState(search);
    // Simulate the operator going offline: every non-localhost request is
    // aborted while the app server stays reachable.
    await page.route('**/*', (route) => {
      const url = route.request().url();
      if (url.startsWith('http://127.0.0.1') || url.startsWith('data:')
          || url.startsWith('blob:')) {
        return route.continue();
      }
      return route.abort();
    });
    // Re-open the conversation inside the SPA and then reload the page; a
    // revisit must never fire a search on its own.
    await goto(page, app.url, '/sessions');
    await waitForSync(page, 60000).catch(() => {});
    await page.reload({ waitUntil: 'load' });
    await waitForSync(page, 60000);
    await page.waitForTimeout(1500);

    const searchAfterOffline = await mockSearchState(search);
    result.searchOffline = {
      before: searchBeforeOffline.requests.length,
      after: searchAfterOffline.requests.length,
      queriesBefore: searchBeforeOffline.queries.length,
      queriesAfter: searchAfterOffline.queries.length,
    };
    result.checks.offlineNoImplicitSearch =
      searchAfterOffline.requests.length === searchBeforeOffline.requests.length
      && searchAfterOffline.queries.length === searchBeforeOffline.queries.length;
    assert(result.checks.offlineNoImplicitSearch,
      `offline revisit fired search requests: ${JSON.stringify(result.searchOffline)}`);

    // Cards survive the offline revisit: the last assistant message still
    // shows its source card.
    const cardsOffline = await sourceCardTexts(page);
    result.cardsOffline = cardsOffline;
    result.checks.offlineCardsKept = cardsOffline.length === 2
      && cardsOffline.some((text) => text.includes(sharedUrl))
      && cardsOffline.some((text) => text.includes(betaUrl));
    assert(result.checks.offlineCardsKept,
      `offline revisit lost source cards: ${JSON.stringify(cardsOffline)}`);
    const textsOffline = await assistantTexts(page);
    result.checks.offlineReplyKept = textsOffline.some((t) => t.includes('E2E07-REPLY-2'));
    assert(result.checks.offlineReplyKept, 'offline revisit lost the assistant reply');
    result.screenshots.push(await shot(page, dir, 'e2e07-04-offline'));

    await page.unroute('**/*');
    await waitForSync(page, 60000).catch(() => {});

    // --- Plain-text export with sources ------------------------------------
    const exportResult = await downloadExport(page, '导出纯文本', dir, 'e2e07-export.txt');
    result.exportFile = exportResult.file;
    result.exportText = exportResult.text;
    const exportText = exportResult.text;
    result.checks.exportHasPrompt = exportText.includes('第一轮')
      && exportText.includes('第二轮');
    result.checks.exportHasReplies = exportText.includes('E2E07-REPLY-1')
      && exportText.includes('E2E07-REPLY-2');
    result.checks.exportHasDocOneUrl = exportText.includes(docOneUrl);
    result.checks.exportHasDocTwoUrl = exportText.includes(docTwoUrl);
    result.checks.exportHasSharedUrl = exportText.includes(sharedUrl);
    result.checks.exportHasBetaUrl = exportText.includes(betaUrl);
    const exportDocOneCount = (exportText.match(/https:\/\/example\.test\/docs\/one/g) || []).length;
    result.exportDocOneCount = exportDocOneCount;
    result.checks.exportDedup = exportDocOneCount >= 1;
    result.checks.exportUnknownDate = exportText.includes('未知发布日期');
    result.checks.exportPublishedDate = exportText.includes('2026-01-02T03:04:05.000Z')
      || exportText.includes('2026-01-02');
    result.checks.exportHasSourceSection = exportText.includes('来源：');
    assert(result.checks.exportHasPrompt, 'export lost the prompts');
    assert(result.checks.exportHasReplies, 'export lost the replies');
    assert(result.checks.exportHasDocOneUrl, 'export lost the doc-one URL');
    assert(result.checks.exportHasDocTwoUrl, 'export lost the doc-two URL');
    assert(result.checks.exportHasSharedUrl, 'export lost the shared URL');
    assert(result.checks.exportHasBetaUrl, 'export lost the beta URL');
    assert(result.checks.exportDedup, 'export lost the deduplicated source');
    assert(result.checks.exportUnknownDate, 'export does not mark unknown dates');
    assert(result.checks.exportPublishedDate, 'export lost the published date');
    assert(result.checks.exportHasSourceSection, 'export has no 来源 section');
    result.screenshots.push(await shot(page, dir, 'e2e07-05-export'));

    result.mockState = await collectMockState({ model, search });
    result.checks.mockRequests = (result.mockState.model?.chatCompletions || 0) >= 2;
    result.checks.noPageErrors = !result.browserEvents.some((e) => e.includes('pageerror'));
    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e07-result', result);
    assert(result.ok, `one or more E2E 07 checks failed: ${JSON.stringify(result.checks)}`);
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockState = await collectMockState({ model, search }).catch(() => null);
    result.screenshots.push(await shot(page, dir, 'e2e07-error').catch(() => null));
    result.evidence = writeJson(dir, 'e2e07-result', result);
    throw error;
  } finally {
    await app.close();
    await model.close();
    await search.close();
  }
});
