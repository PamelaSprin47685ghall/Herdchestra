// E2E 05 (S11): compress, continue twice, undo — and reject an overlapping
// compression range.
//
// One session carries three original turns (fact / unconfirmed assumption /
// cancelled requirement), each with a real mock-model reply.  The operator
// prepares a compression over the whole six-message send range, then a
// second prepare resubmitting that same full range must be refused
// (COMPRESSION_RANGE_OVERLAP): only the first draft row may exist
// afterwards.  The reviewed summary is then applied verbatim — it keeps
// "假设（未确认）" so the spec can prove the summary never upgrades an
// assumption into a fact.  Two continuation turns run on the compressed
// projection; captured model request bodies prove the summary travels once,
// the replaced messages do not, and both continue turns do.  Undoing must
// restore the original range without deleting the continuation messages:
// every earlier message id stays in the transcript, the next model request
// carries the raw history again with no summary entry, the live client shows
// "压缩 已撤销" immediately, and a third turn still gets a reply.
import { test } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert, assertEqual, assistantBodies, assistantTexts, clientBundleInfo, composer,
  goto, newSession, send, sessionModelLabel, shot, turnLive, userTexts,
  waitForShell, waitForSync, writeJson,
} from './support/ui.mjs';
import { sourceTreeSha256, collectMockState } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

const MODEL_ID = 'mock-fast';
const MODEL_NAME = 'Mock Fast';

const SUMMARY_TEXT =
  '事实：部署窗口是周四 20:00。假设（未确认）：用户可能偏好蓝色主题。已取消要求：不要生成 PDF 报告。';

const PROMPT_FACT = 'E2E05 事实：部署窗口是周四 20:00。请只回复：事实。';
const PROMPT_ASSUMPTION = 'E2E05 假设（未确认）：用户可能偏好蓝色主题。请只回复：假设。';
const PROMPT_CANCELLED = 'E2E05 已取消要求：不要生成 PDF 报告。请只回复：取消。';
const PROMPT_CONTINUE_1 = 'E2E05 继续一：请只回复：继续1。';
const PROMPT_CONTINUE_2 = 'E2E05 继续二：请只回复：继续2。';
const PROMPT_AFTER_UNDO = 'E2E05 撤销后：请只回复：撤销后。';

// Markers that only exist inside the compressed (replaced) range.  A model
// request on the compressed projection must not contain them.
const REPLACED_MARKERS = ['请只回复：事实。', '请只回复：假设。', '请只回复：取消。'];

function evidenceDir05() {
  const dir = path.join(REPO_ROOT, 'plans/evidence/e2e-05');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function replyScript() {
  return [
    { match: '继续一', reply: '继续1。' },
    { match: '继续二', reply: '继续2。' },
    { match: '撤销后', reply: '撤销后。' },
    { match: '事实', reply: '事实。' },
    { match: '假设', reply: '假设。' },
    { match: '取消', reply: '取消。' },
    { reply: 'E2E05 未匹配回复。' },
  ];
}

async function mockState(mock) {
  const response = await fetch(`${mock.url}/__mock/state`,
    { signal: AbortSignal.timeout(10000) });
  return response.json();
}

// One atomic in-page read.  Do not use locator.innerText() for a transient
// status node here: a zero-match locator waits for the element to appear, and
// a node that disappears between count() and innerText() waits until the test
// timeout.
async function readTurnSnapshot(page) {
  return page.evaluate(() => {
    const incoming = document.querySelectorAll(
      '.hc-message-in:not(.hc-message-process)');
    const last = incoming.length ? incoming[incoming.length - 1] : null;
    const lastText = last ? (last.innerText || last.textContent || '') : '';
    // Preserve the previous selector's coverage: any incoming status,
    // including a process-owned one; the last matched status wins.
    const statuses = document.querySelectorAll(
      '.hc-message-in .hc-message-status');
    const status = statuses.length ? statuses[statuses.length - 1] : null;
    const statusText = status ? (status.innerText || status.textContent || '') : '';
    const buttons = document.querySelectorAll(
      'button[aria-label="停止当前生成"], button[aria-label="停止无人值守运行"]');
    let stopVisible = false;
    let stopAria = '';
    let stopText = '';
    for (const button of buttons) {
      const visible = button.offsetParent !== null
        || button.getClientRects().length > 0;
      if (!visible) continue;
      stopVisible = true;
      stopAria = button.getAttribute('aria-label') || '';
      stopText = (button.innerText || button.textContent || '').trim();
      break;
    }
    // `getByText(...).count()` matched text regardless of visibility, so use
    // textContent for the waiting indicator rather than innerText.
    const bodyText = document.body.textContent || '';
    return {
      lastText,
      statusText,
      stopVisible,
      stopAria,
      stopText,
      bodyHasWaiting: bodyText.includes('当前轮仍在生成'),
    };
  });
}

async function safeInnerText(locator, timeout = 1000) {
  if ((await locator.count()) === 0) return '';
  return locator.innerText({ timeout }).catch(() => '');
}

// A turn is done for this spec when the scripted reply marker is visible,
// no "正在生成" indicator is on screen, and the mock has been quiet.  The
// server may keep `:run-status :running` while its review ledger is open
// (D-13 boundary) and `turnLive` can stay true on a retained run-id, so it
// is diagnostic only — never the gate.  Never clicks stop.
async function waitTurnDone(page, mock, marker, timeout = 300000) {
  const started = Date.now();
  const tag = `[waitTurnDone ${marker.slice(0, 12)}]`;
  let lastLog = 0;
  let lastMockCount = -1;
  let stablePolls = 0;
  let quietSince = null;
  let lastSnapshot = null;
  try {
    while (Date.now() - started < timeout) {
      if (Date.now() - lastLog > 30000) {
        lastLog = Date.now();
        console.log(`${tag} t=${Math.round((Date.now() - started) / 1000)}s polling`);
      }
      const texts = await assistantTexts(page).catch(() => []);
      const replied = texts.some((text) => text.includes(marker));
      lastSnapshot = await readTurnSnapshot(page);
      const generating = lastSnapshot.bodyHasWaiting ? 1 : 0;
      const streamLive = /正在生成|生成中/.test(lastSnapshot.statusText);
      let mockQuiet = true;
      if (mock) {
        const state = await mockState(mock).catch(() => null);
        const count = state ? state.requests : -1;
        if (count !== lastMockCount) {
          lastMockCount = count;
          stablePolls = 0;
        } else {
          stablePolls += 1;
        }
        mockQuiet = stablePolls >= 4;
      }
      if (replied && generating === 0 && !streamLive && mockQuiet) {
        if (quietSince === null) quietSince = Date.now();
        if (Date.now() - quietSince >= 2000) return true;
      } else {
        quietSince = null;
      }
      await page.waitForTimeout(500);
    }
  } catch (error) {
    waitTurnDone.lastDiagnostics = {
      pageClosed: true,
      error: String(error),
      lastAssistant: lastSnapshot?.lastText || '',
      pageTextTail: lastSnapshot?.lastText || '',
      lastSnapshot,
    };
    return false;
  }
  const diagnostics = {
    pageTextTail: (await safeInnerText(page.locator('body'), 1000)).slice(-3000),
    lastAssistant: ((await assistantTexts(page).catch(() => [])).slice(-1)[0] || ''),
    turnLive: await turnLive(page).catch(() => null),
    mockRequests: mock ? (await mockState(mock).catch(() => null))?.requests : null,
    lastSnapshot,
  };
  waitTurnDone.lastDiagnostics = diagnostics;
  return false;
}

async function sendAndSettle(page, mock, text, replyMarker) {
  console.log(`[sendAndSettle] sending "${text.slice(0, 30)}" at ${new Date().toISOString()}`);
  await waitForSync(page, 30000);
  await send(page, text);
  console.log(`[sendAndSettle] sent at ${new Date().toISOString()}`);
  const settled = await waitTurnDone(page, mock, replyMarker || text.slice(0, 12));
  console.log(`[sendAndSettle] settle=${settled} at ${new Date().toISOString()}`);
  if (!settled) {
    const diag = waitTurnDone.lastDiagnostics || {};
    throw new Error(`turn did not settle for "${text.slice(0, 40)}" marker "${replyMarker}"\n` +
      `lastAssistant: ${(diag.lastAssistant || '').slice(0, 300)}\n` +
      `turnLive: ${diag.turnLive}\n` +
      `mockRequests: ${diag.mockRequests}\n` +
      `pageTail: ${(diag.pageTextTail || '').slice(-1500)}`);
  }
  await page.waitForTimeout(800);
  return true;
}

// Prepare a compression draft, retrying while a run is still settling (a
// prepare submitted mid-turn can be refused while the outbound range is in
// flux).  Each retry waits for the run to go quiet before resubmitting.
async function prepareDraftWithRetry(page, mock, summary, keepCount, attempts = 3) {
  let lastRows = [];
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    await openContextPanel(page);
    await startPreparing(page);
    const sel = await setPreparerSelection(page, keepCount);
    await submitPreparation(page, summary);
    try {
      await waitForCompressionRow(page, '压缩 草稿：', 15000);
      return { ok: true, attempt, selection: sel };
    } catch {
      lastRows = await compressionRows(page).allInnerTexts().catch(() => []);
      // The rejected prepare leaves the editor open; close it and wait for
      // the session to go quiet before retrying.
      await preparerGroup(page).getByRole('button', { name: '取消', exact: true })
        .first().click().catch(() => {});
      await page.waitForTimeout(5000);
      await waitTurnDone(page, mock, '取消。', 60000).catch(() => false);
    }
  }
  return { ok: false, rows: lastRows };
}

function contextPanel(page) {
  return page.locator('details.hc-context-panel[aria-label="上下文与压缩"]').first();
}

async function openContextPanel(page) {
  const panel = contextPanel(page);
  await panel.waitFor({ state: 'visible', timeout: 30000 });
  // `<details open>` reports getAttribute('open') as "" (falsy), so the
  // attribute string cannot detect the open state; ask the element itself.
  if (!(await panel.evaluate((element) => element.open))) {
    await panel.locator('summary').first().click();
  }
  return panel;
}

async function panelHeaderText(panel) {
  return safeInnerText(panel.locator('.hc-context-header').first());
}

function compressionRows(page) {
  return contextPanel(page).locator('.hc-context-compression');
}

function compressionPreparer(page) {
  return contextPanel(page).locator('.hc-compression-preparer').first();
}

function preparerGroup(page) {
  return compressionPreparer(page).locator('[aria-label="准备压缩"]').first();
}

async function startPreparing(page) {
  const button = compressionPreparer(page)
    .getByRole('button', { name: '压缩当前发送范围', exact: true }).first();
  await button.waitFor({ state: 'visible', timeout: 15000 });
  await button.click();
  await preparerGroup(page).waitFor({ state: 'visible', timeout: 10000 });
}

function preparerCheckboxes(page) {
  return preparerGroup(page).locator('.hc-ask-option input[type="checkbox"]');
}

// Keep the first `keepCount` checkboxes checked, uncheck the rest; a null
// keepCount keeps every box checked (full-range resubmission).  The
// checkbox order follows the outbound `:message-ids` order, which is the
// visible message order.
async function setPreparerSelection(page, keepCount) {
  const boxes = preparerCheckboxes(page);
  const total = await boxes.count();
  const keep = keepCount ?? total;
  assert(total >= keep, `preparer shows ${total} ids, need ${keep}`);
  for (let index = keep; index < total; index += 1) {
    const box = boxes.nth(index);
    if (await box.isChecked()) await box.uncheck();
  }
  for (let index = 0; index < keep; index += 1) {
    const box = boxes.nth(index);
    if (!(await box.isChecked())) await box.check();
  }
  const checked = await preparerGroup(page)
    .locator('.hc-ask-option input[type="checkbox"]:checked').count();
  return { total, checked };
}

async function submitPreparation(page, summary) {
  const group = preparerGroup(page);
  await group.locator('textarea[aria-label="压缩摘要"]').first().fill(summary);
  await group.getByRole('button', { name: '准备压缩', exact: true }).first().click();
}

async function waitForCompressionRow(page, label, timeout = 30000) {
  const row = compressionRows(page).filter({ hasText: label }).first();
  await row.waitFor({ state: 'visible', timeout });
  return row;
}

async function messageIds(page) {
  return page.locator('.hc-message-out:not(.hc-message-process), .hc-message-in:not(.hc-message-process)')
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-message-id') || ''));
}

function analyzeRequestBody(raw) {
  let body = null;
  try { body = JSON.parse(raw); } catch { return null; }
  const messages = Array.isArray(body?.messages) ? body.messages : [];
  const flat = messages.map((message) => {
    const content = message?.content;
    if (typeof content === 'string') return content;
    if (Array.isArray(content)) {
      return content
        .map((part) => (part && part.type === 'text' ? part.text : ''))
        .join('');
    }
    return '';
  });
  const joined = flat.join('\n');
  return {
    roles: messages.map((message) => message?.role),
    flat,
    hasSummary: flat.some((text) => text.includes(SUMMARY_TEXT)),
    hasContinue1Prompt: flat.some((text) => text.includes(PROMPT_CONTINUE_1)),
    hasContinue1Reply: flat.some((text) => text.trim() === '继续1。'),
    hasContinue2Prompt: flat.some((text) => text.includes(PROMPT_CONTINUE_2)),
    hasContinue2Reply: flat.some((text) => text.trim() === '继续2。'),
    hasAfterUndoPrompt: flat.some((text) => text.includes(PROMPT_AFTER_UNDO)),
    hasAfterUndoReply: flat.some((text) => text.trim() === '撤销后。'),
    promptFactPresent: flat.some((text) => text.includes(PROMPT_FACT)),
    promptAssumptionPresent: flat.some((text) => text.includes(PROMPT_ASSUMPTION)),
    promptCancelledPresent: flat.some((text) => text.includes(PROMPT_CANCELLED)),
    replacedPresent: REPLACED_MARKERS.filter((marker) => joined.includes(marker)),
  };
}

async function analyzedBodies(mock) {
  const state = await mockState(mock);
  return {
    count: state.requests,
    bodies: (state.bodies || []).map(analyzeRequestBody).filter(Boolean),
  };
}

// The model request that answers the newest user turn proves what the
// (compressed or restored) projection actually sent.
function requestCarrying(bodies, prompt) {
  return bodies.filter((body) => body.flat.some((text) => text.includes(prompt)));
}

test('@core E2E 05 压缩后继续与撤销', async ({ page, browser }) => {
  // Six model turns plus prepare/apply/undo UI round-trips; under parallel
  // e2e load each turn can take a minute, so the budget is generous.
  test.setTimeout(900000);
  const dir = evidenceDir05();
  const marker = `E2E05S11-${Date.now()}`;

  const model = await startMockModel({
    replyScript: replyScript(),
    chunkDelayMs: 5,
    captureBodies: true,
  });
  const app = await startApp({
    evidenceDir: dir,
    nudgeRoundBudget: 2,
    models: {
      connections: [{
        id: 'mock',
        name: 'Mock 模型服务',
        baseUrl: `${model.url}/v1`,
        models: [{
          id: MODEL_ID,
          displayName: MODEL_NAME,
          maxOutputTokens: 2048,
          capabilities: ['tools'],
        }],
      }],
      roles: {
        main: { connection: 'mock', model: MODEL_ID },
        task: { connection: 'mock', model: MODEL_ID },
      },
    },
  });

  const result = {
    test: 'E2E 05 S11',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    mockUrl: model.url,
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    bundle: clientBundleInfo().sha256,
    tree: sourceTreeSha256(),
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

  const checks = result.checks;
  try {
    // --- Three original turns -------------------------------------------
    await goto(page, app.url, '/sessions');
    await waitForShell(page);
    await waitForSync(page);
    await newSession(page);
    const modelLabel = await sessionModelLabel(page);
    result.modelLabel = modelLabel;
    checks.modelBound = modelLabel.includes(MODEL_NAME) || modelLabel.includes(MODEL_ID);
    assert(checks.modelBound, `session did not bind ${MODEL_NAME}: ${modelLabel}`);

    checks.turn1Settled = await sendAndSettle(page, model, PROMPT_FACT, '事实。');
    assert(checks.turn1Settled, 'turn 1 did not settle');
    checks.turn2Settled = await sendAndSettle(page, model, PROMPT_ASSUMPTION, '假设。');
    assert(checks.turn2Settled, 'turn 2 did not settle');
    checks.turn3Settled = await sendAndSettle(page, model, PROMPT_CANCELLED, '取消。');
    assert(checks.turn3Settled, 'turn 3 did not settle');

    const usersBefore = await userTexts(page);
    const assistantsBefore = await assistantTexts(page);
    result.usersBefore = usersBefore;
    result.assistantsBefore = assistantsBefore.map((text) => text.slice(0, 120));
    checks.originalTurnsReplied =
      assistantsBefore.some((text) => text.includes('事实。'))
      && assistantsBefore.some((text) => text.includes('假设。'))
      && assistantsBefore.some((text) => text.includes('取消。'));
    assert(checks.originalTurnsReplied,
      `original replies missing: ${JSON.stringify(result.assistantsBefore)}`);

    const idsBefore = await messageIds(page);
    result.messageIdsBefore = idsBefore;
    checks.sixMessagesVisible = idsBefore.length === 6 && idsBefore.every(Boolean);
    assert(checks.sixMessagesVisible,
      `expected 6 message rows with ids, got ${JSON.stringify(idsBefore)}`);
    result.screenshots.push(await shot(page, dir, 'e2e05-01-three-turns'));

    // --- Prepare the full-range draft, then probe an overlapping range ----
    // Nudge records may still sit inside :message-order right after the last
    // reply; the prepare is retried until the visible order is contiguous.
    const prepared = await prepareDraftWithRetry(
      page, model, 'E2E05 重叠探测前的全范围草稿摘要。', 6);
    checks.fullDraftCreated = prepared.ok === true;
    checks.fullRangeSelection = prepared.selection || null;
    result.prepareAttempts = prepared;
    assert(checks.fullDraftCreated,
      `full-range prepare never produced a draft: ${JSON.stringify(prepared.rows || prepared)}`);

    // A second prepare resubmitting the full send range overlaps the
    // existing draft on every id and must be refused.  The editor reopens
    // with the full range pre-checked; keep every box checked so the
    // submitted range is identical to the draft's.
    await startPreparing(page);
    const selOverlap = await setPreparerSelection(page, null);
    checks.overlapAttemptSelection = selOverlap;
    await submitPreparation(page, 'E2E05 重叠范围摘要（应被拒绝）。');
    await page.waitForTimeout(5000);
    const rowsAfterOverlap = await compressionRows(page).allInnerTexts();
    result.rowsAfterOverlapAttempt = rowsAfterOverlap;
    checks.overlapRejected =
      rowsAfterOverlap.filter((text) => text.includes('压缩 草稿：')).length === 1
      && !rowsAfterOverlap.some((text) => text.includes('压缩 已采用：'))
      && !rowsAfterOverlap.some((text) => text.includes('重叠范围摘要'));
    assert(checks.overlapRejected,
      `overlapping prepare was not rejected: ${JSON.stringify(rowsAfterOverlap)}`);
    const headerAfterOverlap = await panelHeaderText(await openContextPanel(page));
    result.headerAfterOverlap = headerAfterOverlap;
    checks.overlapHeaderUnchanged = /摘要\s*0/.test(headerAfterOverlap);
    assert(checks.overlapHeaderUnchanged,
      `header changed after rejected prepare: ${headerAfterOverlap}`);
    result.screenshots.push(await shot(page, dir, 'e2e05-02-overlap-rejected'));

    // --- Apply the reviewed summary --------------------------------------
    // The surviving draft still holds the placeholder summary; the applied
    // record must carry the reviewed text.  `apply-compression!` accepts a
    // `:summary` override, and the row's "使用摘要" button submits the
    // textarea content — but applied rows never rewrite the frozen summary,
    // so the reviewed text must be set BEFORE apply.  The draft row's
    // textarea is editable; fill it with the reviewed summary, then apply.
    const draftRow = await waitForCompressionRow(page, '压缩 草稿：', 15000);
    await draftRow.locator('textarea[aria-label="压缩摘要"]').first()
      .fill(SUMMARY_TEXT);
    await draftRow.getByRole('button', { name: '使用摘要', exact: true }).first().click();
    const appliedRow = await waitForCompressionRow(page, '压缩 已采用：', 30000);
    const appliedRowText = await safeInnerText(appliedRow);
    result.appliedRowText = appliedRowText;
    checks.appliedVerbatim = appliedRowText.includes(SUMMARY_TEXT);
    checks.assumptionNotUpgraded =
      appliedRowText.includes('假设（未确认）')
      && !appliedRowText.includes('用户偏好蓝色主题是事实');
    assert(checks.appliedVerbatim, `applied row lost the reviewed summary: ${appliedRowText}`);
    assert(checks.assumptionNotUpgraded,
      `summary upgraded the assumption: ${appliedRowText}`);

    const headerAfterApply = await panelHeaderText(await openContextPanel(page));
    result.headerAfterApply = headerAfterApply;
    checks.appliedHeader = /摘要\s*1/.test(headerAfterApply);
    assert(checks.appliedHeader, `header does not show one summary: ${headerAfterApply}`);
    result.screenshots.push(await shot(page, dir, 'e2e05-03-applied'));

    // Original messages stay readable in the transcript after apply.
    const usersAfterApply = await userTexts(page);
    const assistantsAfterApply = await assistantTexts(page);
    checks.originalsReadableAfterApply =
      usersAfterApply.filter((text) => text.includes('请只回复：事实。')).length === 1
      && usersAfterApply.some((text) => text.includes('请只回复：假设。'))
      && usersAfterApply.some((text) => text.includes('请只回复：取消。'))
      && assistantsAfterApply.some((text) => text.includes('事实。'))
      && assistantsAfterApply.some((text) => text.includes('假设。'))
      && assistantsAfterApply.some((text) => text.includes('取消。'));
    assert(checks.originalsReadableAfterApply,
      'original messages are not readable after apply');

    // --- Continue two rounds on the compressed projection ----------------
    checks.continue1Settled = await sendAndSettle(page, model, PROMPT_CONTINUE_1, '继续1。');
    assert(checks.continue1Settled, 'continue 1 did not settle');
    checks.continue2Settled = await sendAndSettle(page, model, PROMPT_CONTINUE_2, '继续2。');
    assert(checks.continue2Settled, 'continue 2 did not settle');

    const assistantsAfterContinue = await assistantTexts(page);
    checks.continueReplies =
      assistantsAfterContinue.some((text) => text.includes('继续1。'))
      && assistantsAfterContinue.some((text) => text.includes('继续2。'));
    assert(checks.continueReplies, 'continue replies missing');

    // The request answering 继续二 is the compressed projection in flight:
    // summary present once, both continue turns present, replaced range absent.
    let captured = await analyzedBodies(model);
    result.capturedCountAfterContinue = captured.count;
    result.capturedBodiesAfterContinue = captured.bodies.map((body) => ({
      roles: body.roles,
      hasSummary: body.hasSummary,
      hasContinue1Prompt: body.hasContinue1Prompt,
      hasContinue1Reply: body.hasContinue1Reply,
      hasContinue2Prompt: body.hasContinue2Prompt,
      hasContinue2Reply: body.hasContinue2Reply,
      promptFactPresent: body.promptFactPresent,
      promptAssumptionPresent: body.promptAssumptionPresent,
      promptCancelledPresent: body.promptCancelledPresent,
      replacedPresent: body.replacedPresent,
    }));
    let req2 = requestCarrying(captured.bodies, PROMPT_CONTINUE_2).at(-1);
    checks.continue2RequestFound = Boolean(req2);
    assert(checks.continue2RequestFound, 'no model request carried the continue-2 turn');
    checks.compressedRequestHasSummary = req2.hasSummary;
    checks.compressedRequestSummaryVerbatim = req2.hasSummary;
    checks.compressedRequestHasBothContinues =
      req2.hasContinue1Prompt && req2.hasContinue1Reply
      && req2.hasContinue2Prompt;
    checks.compressedRequestExcludesReplaced = req2.replacedPresent.length === 0;
    checks.compressedRequestKeepsPrompts =
      !req2.promptFactPresent && !req2.promptAssumptionPresent && !req2.promptCancelledPresent;
    assert(checks.compressedRequestHasSummary, 'compressed request lacks the summary');
    assert(checks.compressedRequestHasBothContinues,
      `compressed request lost continue turns: ${JSON.stringify(req2)}`);
    assert(checks.compressedRequestExcludesReplaced,
      `compressed request still carries replaced messages: ${JSON.stringify(req2.replacedPresent)}`);

    const headerAfterContinue = await panelHeaderText(await openContextPanel(page));
    result.headerAfterContinue = headerAfterContinue;
    result.screenshots.push(await shot(page, dir, 'e2e05-04-continued-twice'));

    const idsBeforeUndo = await messageIds(page);
    result.messageIdsBeforeUndo = idsBeforeUndo;
    checks.messagesBeforeUndo = idsBeforeUndo.length;

    // --- Undo restores the original range -------------------------------
    await openContextPanel(page);
    const appliedBeforeUndo = await waitForCompressionRow(page, '压缩 已采用：', 15000);
    await appliedBeforeUndo.getByRole('button', { name: '撤销压缩', exact: true })
      .first().click();
    // The live client must mark the row reverted without a reload.
    const revertedRow = await waitForCompressionRow(page, '压缩 已撤销：', 30000);
    const revertedRowText = await safeInnerText(revertedRow);
    result.revertedRowText = revertedRowText;
    checks.undoMarkedLive = revertedRowText.includes('压缩 已撤销');
    assert(checks.undoMarkedLive, `undo not marked live: ${revertedRowText}`);
    result.screenshots.push(await shot(page, dir, 'e2e05-05-undone-live'));

    const headerAfterUndo = await panelHeaderText(await openContextPanel(page));
    result.headerAfterUndo = headerAfterUndo;
    checks.undoRestoresRange =
      /摘要\s*0/.test(headerAfterUndo) && /发送\s*10\s*条/.test(headerAfterUndo);
    assert(checks.undoRestoresRange,
      `undo did not restore the original range: ${headerAfterUndo}`);

    // Continuation answers survive the undo; no message was deleted.
    const usersAfterUndo = await userTexts(page);
    const assistantsAfterUndo = await assistantTexts(page);
    checks.answersSurviveUndo =
      usersAfterUndo.some((text) => text.includes('继续一'))
      && usersAfterUndo.some((text) => text.includes('继续二'))
      && assistantsAfterUndo.some((text) => text.includes('继续1。'))
      && assistantsAfterUndo.some((text) => text.includes('继续2。'));
    assert(checks.answersSurviveUndo, 'continuation messages lost after undo');

    const idsAfterUndo = await messageIds(page);
    result.messageIdsAfterUndo = idsAfterUndo;
    checks.noMessageDeleted =
      idsBeforeUndo.length > 0
      && idsBeforeUndo.every((id) => idsAfterUndo.includes(id));
    assert(checks.noMessageDeleted,
      `undo deleted messages: before=${JSON.stringify(idsBeforeUndo)} after=${JSON.stringify(idsAfterUndo)}`);

    // Originals are readable again in full.
    checks.originalsReadableAfterUndo =
      usersAfterUndo.filter((text) => text.includes('请只回复：事实。')).length === 1
      && usersAfterUndo.some((text) => text.includes('请只回复：假设。'))
      && usersAfterUndo.some((text) => text.includes('请只回复：取消。'));
    assert(checks.originalsReadableAfterUndo, 'original messages not readable after undo');

    // --- One more turn proves the restored range is what gets sent --------
    checks.afterUndoSettled = await sendAndSettle(page, model, PROMPT_AFTER_UNDO, '撤销后。');
    assert(checks.afterUndoSettled, 'post-undo turn did not settle');
    const assistantsFinal = await assistantTexts(page);
    checks.afterUndoReply = assistantsFinal.some((text) => text.includes('撤销后。'));
    assert(checks.afterUndoReply, 'post-undo reply missing');

    captured = await analyzedBodies(model);
    result.capturedCountFinal = captured.count;
    result.capturedBodiesFinal = captured.bodies.map((body) => ({
      roles: body.roles,
      hasSummary: body.hasSummary,
      hasContinue1Prompt: body.hasContinue1Prompt,
      hasContinue2Prompt: body.hasContinue2Prompt,
      hasContinue2Reply: body.hasContinue2Reply,
      hasAfterUndoPrompt: body.hasAfterUndoPrompt,
      hasAfterUndoReply: body.hasAfterUndoReply,
      promptFactPresent: body.promptFactPresent,
      promptAssumptionPresent: body.promptAssumptionPresent,
      promptCancelledPresent: body.promptCancelledPresent,
      replacedPresent: body.replacedPresent,
    }));
    const reqUndo = requestCarrying(captured.bodies, PROMPT_AFTER_UNDO).at(-1);
    checks.undoRequestFound = Boolean(reqUndo);
    assert(checks.undoRequestFound, 'no model request carried the post-undo turn');
    checks.undoSendsRestoredHistory =
      reqUndo.promptFactPresent
      && reqUndo.promptAssumptionPresent
      && reqUndo.promptCancelledPresent
      && reqUndo.hasContinue1Prompt && reqUndo.hasContinue2Prompt
      && reqUndo.hasContinue2Reply;
    checks.undoDropsSummary = !reqUndo.hasSummary;
    checks.undoRequestHasNewMessage = reqUndo.hasAfterUndoPrompt;
    assert(checks.undoSendsRestoredHistory,
      `post-undo request lost restored history: ${JSON.stringify(reqUndo)}`);
    assert(checks.undoDropsSummary, 'post-undo request still carries the summary');
    assert(checks.undoRequestHasNewMessage, 'post-undo request lacks the new turn');
    result.screenshots.push(await shot(page, dir, 'e2e05-06-after-undo-turn'));

    result.mockState = await collectMockState({ model });
    result.finishedAt = new Date().toISOString();
    result.ok = Object.values(checks).every(Boolean);
    result.evidence = writeJson(dir, 'e2e05-result', result);
    assert(result.ok, `E2E 05 checks failed: ${JSON.stringify(checks)}`);
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    try {
      result.screenshots.push(await shot(page, dir, 'e2e05-failure'));
    } catch { /* page may be gone */ }
    result.mockState = await collectMockState({ model }).catch(() => null);
    result.evidence = writeJson(dir, 'e2e05-result', result);
    throw error;
  } finally {
    await app.close();
    await model.close();
  }
});
