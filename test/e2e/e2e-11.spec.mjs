// E2E 11: 长期记忆候选/归档/临时会话.
//
// The app is the real server + real UI + real Sente command channel.  One
// mock OpenAI-compatible model serves two roles on one connection:
//   - `mock-e2e11-main`  streams plain replies and records every request
//     body so the test can prove whether the memory block reached the model.
//   - `mock-e2e11-task`  answers memory-suggestion calls with scripted JSON
//     candidate lists (one candidate per call) so the 待审 -> 批准/拒绝 ->
//     改范围 -> 归档 flow is deterministic.
//
// Covered by the checks in `result.checks`:
//   defaults-off        both memory switches start off
//   switches-independent read/suggest toggles act independently
//   suggest-candidate   整理记忆 produces 待审 candidates only
//   approve / reject    candidate review writes real statuses
//   rescope-approve     approving after editing the scope applies it
//   inject-active       an approved global memory reaches the model
//   archive-stops       archiving removes the memory from later requests
//   temp-session        temporary sessions read/write nothing, and the
//                       one-shot temporary read is session-scoped and
//                       cleared after one send
import { test } from '@playwright/test';
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { startApp, REPO_ROOT } from './support/app.mjs';
import {
  assert, assertEqual, composer, goto, lastAssistantText, newSession,
  selectFirstSession, send, shot, waitForShell, waitForSync,
  writeJson, clientBundleInfo, turnLive,
} from './support/ui.mjs';
import { sourceTreeSha256 } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

const EVIDENCE = path.join(REPO_ROOT, 'plans/evidence/e2e-11');
const MAIN_MODEL = 'mock-e2e11-main';
const TASK_MODEL = 'mock-e2e11-task';
const MAIN_REPLY = 'E2E11 mock reply：记忆注入验证。';
const CANDIDATE_TEXTS = [
  'E2E11 候选甲：用户偏好简短回答。',
  'E2E11 候选乙：用户要求每次先确认范围。',
  'E2E11 候选丙：用户希望回复带出处。',
];

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

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (c) => { body += c; });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

// Mock model: `mock-e2e11-main` streams plain text; `mock-e2e11-task`
// answers with a JSON candidate list that advances per call.  Every request
// body is stored (bounded) so injection can be proven on the wire.
async function startE2e11Mock() {
  const state = {
    requests: [],       // {at, model, stream, messageCount}
    bodies: [],         // raw request bodies, newest last, bounded
    taskCalls: 0,
    mainCalls: 0,
  };
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (req.method === 'GET' && url.pathname === '/v1/models') {
      const body = JSON.stringify({
        object: 'list',
        data: [MAIN_MODEL, TASK_MODEL].map((id) => ({
          id, object: 'model', owned_by: 'e2e11-mock',
        })),
      });
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(body);
    }
    if (req.method === 'GET' && url.pathname === '/__mock/state') {
      const body = JSON.stringify({
        mock: true,
        requests: state.requests.length,
        mainCalls: state.mainCalls,
        taskCalls: state.taskCalls,
        log: state.requests.slice(-50),
        bodies: state.bodies,
      });
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
    const model = String(body.model || MAIN_MODEL);
    const stream = body.stream === true;
    const id = `chatcmpl-e2e11-${state.requests.length}`;
    state.requests.push({
      at: new Date().toISOString(), model, stream,
      messageCount: Array.isArray(body.messages) ? body.messages.length : 0,
    });
    state.bodies.push(raw.slice(0, 200000));
    if (state.bodies.length > 40) {
      state.bodies.shift();
      state.requests.shift();
    }
    if (model.includes('task')) {
      state.taskCalls += 1;
      const index = Math.min(state.taskCalls - 1, CANDIDATE_TEXTS.length - 1);
      const text = CANDIDATE_TEXTS[index];
      const reply = JSON.stringify([{
        text,
        category: 'expression-preference',
        epistemic: 'preference',
        confidence: 0.9,
        quote: 'E2E11',
      }]);
      if (!stream) {
        const payload = {
          id, object: 'chat.completion',
          created: Math.floor(Date.now() / 1000), model,
          choices: [{ index: 0,
                      message: { role: 'assistant', content: reply },
                      finish_reason: 'stop' }],
          usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 },
        };
        const out = JSON.stringify(payload);
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(out);
      }
      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache', connection: 'keep-alive',
        'x-herdchestra-mock': 'e2e11-model',
      });
      res.flushHeaders?.();
      sse(res, chunk(id, model, { role: 'assistant', content: '' }));
      for (let i = 0; i < reply.length; i += 24) {
        sse(res, chunk(id, model, { content: reply.slice(i, i + 24) }));
      }
      sse(res, chunk(id, model, {}, 'stop'));
      res.write('data: [DONE]\n\n');
      return res.end();
    }
    state.mainCalls += 1;
    if (!stream) {
      const payload = {
        id, object: 'chat.completion',
        created: Math.floor(Date.now() / 1000), model,
        choices: [{ index: 0,
                    message: { role: 'assistant', content: MAIN_REPLY },
                    finish_reason: 'stop' }],
        usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 },
      };
      const out = JSON.stringify(payload);
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(out);
    }
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache', connection: 'keep-alive',
      'x-herdchestra-mock': 'e2e11-model',
    });
    res.flushHeaders?.();
    sse(res, chunk(id, model, { role: 'assistant', content: '' }));
    for (let i = 0; i < MAIN_REPLY.length; i += 12) {
      sse(res, chunk(id, model, { content: MAIN_REPLY.slice(i, i + 12) }));
    }
    sse(res, chunk(id, model, {}, 'stop'));
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

// The system messages the adapter actually sent for one request body.  The
// role prompt and the materials injection are separate `:system` messages,
// so join them before looking for the Memory section.
function systemTextOf(rawBody) {
  try {
    const parsed = JSON.parse(rawBody);
    return (Array.isArray(parsed.messages) ? parsed.messages : [])
      .filter((message) => message && message.role === 'system')
      .map((message) => (typeof message.content === 'string'
                         ? message.content
                         : JSON.stringify(message.content)))
      .join('\n');
  } catch {
    return '';
  }
}

function memorySectionOf(rawBody) {
  const system = systemTextOf(rawBody);
  const at = system.indexOf('Memory:');
  if (at < 0) return null;
  return system.slice(at);
}

function requestHasUserText(rawBody, marker) {
  try {
    const parsed = JSON.parse(rawBody);
    return (parsed.messages || []).some((message) =>
      message && message.role === 'user'
        && typeof message.content === 'string'
        && message.content.includes(marker));
  } catch {
    return false;
  }
}

// Each send can produce more than one main-model request (the materials
// context call and a later continuation).  Injection lives on the context
// call, which is the larger request for the same user text; pick that one
// instead of blindly taking the newest main body.
async function lastMainBody(mock, marker = '') {
  const state = await mockState(mock);
  const requests = Array.isArray(state.requests)
    ? state.requests
    : (state.log || []);
  const bodies = state.bodies || [];
  let best = null;
  for (let i = 0; i < requests.length; i += 1) {
    const request = requests[i];
    const raw = bodies[i] || '';
    if (!request || request.model !== MAIN_MODEL) continue;
    if (marker && !requestHasUserText(raw, marker)) continue;
    if (!best
        || (request.messageCount || 0) > (best.request.messageCount || 0)) {
      best = { request, raw };
    }
  }
  return best ? best.raw : '';
}

// Wait until the mock has served `count` main-model requests with a quiet
// window, so a send turn is genuinely finished before the next assertion.
async function waitMockQuiet(mock, count, timeout = 120000) {
  const started = Date.now();
  let stable = 0;
  let last = -1;
  while (Date.now() - started < timeout) {
    const state = await mockState(mock).catch(() => null);
    const n = state ? state.mainCalls : -1;
    if (n >= count && n === last) {
      stable += 1;
      if (stable >= 4) return true;
    } else {
      stable = 0;
      last = n;
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  return false;
}
// A rejected send leaves no user bubble and no model request, so the next
// assertion reads "turn did not settle" without naming the cause.
//
// Three different gates can refuse a send, and they need different answers:
//
//   * `:generating` — the previous turn is still streaming.  The send opens
//     the "stop-and-send / continue-editing" banner instead of submitting
//     (`decisionBanner`), and nothing reaches the model.  Wait for the
//     previous turn to finish; that is what this helper's callers mean.
//   * `:duplicate` — an earlier send envelope has no receipt yet.  The
//     composer keeps the draft and says nothing.  Wait for the pending list
//     to empty.
//   * `:unattended` — the unattended driver owns the turn.  Not applicable
//     here; it would be a real failure.
//
// The send button's own enabled state is NOT the signal: `request-send!`
// hands the DOM snapshot to the bridge whenever the textarea holds text, so a
// `:disabled` intent still submits.
async function turnGate(page) {
  return page.evaluate(() => {
    const probe = window.__HC_DB__;
    const db = typeof probe === 'function' ? probe() : null;
    // `intent` is a view-model derivative, not stored state, so the DOM
    // banner is the observable stand-in for `:generating`.  Pending sends are
    // in the database and cover the silent `:duplicate` gate.
    return {
      decision: Boolean(document.querySelector(
        '[role="group"][aria-label="当前轮仍在生成"]')),
      pending: db && Array.isArray(db.pendingCommands)
        ? db.pendingCommands.length : 0,
      // `aria-label="停止当前生成"` is mounted even while idle: the control
      // stays in the DOM and its *label text* changes (`停止` when there is
      // nothing to stop).  Its presence is therefore not a turn signal, and
      // neither is `disabled` (only `stopping?` and a missing callback set
      // it).  The decision banner and the pending list are the real gates.
    };
  });
}

async function waitTurnGateClear(page, timeout = 90000) {
  const deadline = Date.now() + timeout;
  let last = null;
  while (Date.now() < deadline) {
    last = await turnGate(page).catch(() => null);
    if (last && !last.decision && last.pending === 0) {
      return { cleared: true, state: last };
    }
    await page.waitForTimeout(250);
  }
  return { cleared: false, state: last };
}

async function userBubbleCount(page) {
  return page.evaluate(() => document.querySelectorAll(
    '.hc-message-out .hc-message-body').length);
}

async function waitUserBubble(page, count, timeout = 20000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if ((await userBubbleCount(page)) >= count) return true;
    await page.waitForTimeout(200);
  }
  return false;
}

// Send TEXT and wait for the turn to settle.
//
// The gate can close again between the wait and the click: the unattended
// driver (`nudgeRoundBudget`) starts a follow-up turn of its own, so "idle"
// is a window, not a state.  When the click lands inside a live turn the
// composer opens the "stop-and-send / continue-editing" banner instead of
// submitting, and the draft stays in the box.  That banner is the product's
// own answer to this situation, so take it: stop the current round and send.
// Anything else (a pending envelope, a missing model) is a real failure.
async function clickStopAndSend(page) {
  const button = page.locator(
    '[role="group"][aria-label="当前轮仍在生成"] button',
    { hasText: '停止当前轮并发送' }).first();
  const present = await button.count()
    .then((n) => n > 0).catch(() => false);
  if (!present) return false;
  await button.click({ timeout: 5000 }).catch(() => {});
  return true;
}

async function sendAndSettle(page, mock, text, expectedMainCalls) {
  await waitForSync(page, 30000);
  const gate = await waitTurnGateClear(page);
  assert(gate.cleared,
         `the previous turn never finished before sending ${JSON.stringify(text)}`
         + ` (gate=${JSON.stringify(gate.state)})`);
  const before = await lastAssistantText(page).catch(() => '');
  const bubbles = await userBubbleCount(page);
  await send(page, text);
  let accepted = await waitUserBubble(page, bubbles + 1);
  if (!accepted) {
    // A live turn opened the decision banner instead of submitting.
    if (await clickStopAndSend(page)) {
      accepted = await waitUserBubble(page, bubbles + 1, 60000);
    }
  }
  assert(accepted,
         `the send was refused, no user bubble appeared for ${JSON.stringify(text)}`
         + ` (diag=${JSON.stringify(
           await page.evaluate(() => {
             const input = document.querySelector('.hc-composer textarea');
             const probe = window.__HC_DB__;
             const db = typeof probe === 'function' ? probe() : null;
             return {
               composer: input ? input.value.slice(0, 80) : '<no textarea>',
               pending: db ? db.pendingCommands : '<no probe>',
               intent: db && db.composerIntent ? db.composerIntent : '<no probe>',
               decisionBanner: Boolean(document.querySelector(
                 '[role="group"][aria-label="当前轮仍在生成"]')),
             };
           }).catch((error) => `unreadable: ${String(error)}`))})`);
  const settled = await waitMockQuiet(mock, expectedMainCalls);
  assert(settled, `turn did not settle: expected ${expectedMainCalls} main calls`
         + ` after ${JSON.stringify(text)}`);
  await page.waitForTimeout(800);
  const after = await lastAssistantText(page).catch(() => '');
  return { before, after };
}

// Materials page helpers ----------------------------------------------------

// Open the materials page and wait until the projection the view renders
// actually describes the session the operator selected.
//
// "材料库已就绪" alone is not that signal.  The badge is driven by the
// client projection, which survives a session switch as `ready? false`; and
// the server answers materials snapshots for *its* current session, which
// lags a click by a command round trip.  When the two disagree the client
// correctly discards the snapshot as cross-session, so the page can sit on a
// ready badge with zero rows while the server holds data for another session.
// Require the two ids to agree, and report them when they do not.
async function materialsAgreement(page) {
  return page.evaluate(() => {
    const probe = window.__HC_DB__;
    const db = typeof probe === 'function' ? probe() : null;
    if (!db || !db.materials) return { probe: 'absent' };
    const server = db.materials;
    const ui = server.ui || {};
    return {
      serverSessionId: server.sessionId,
      serverReady: server.ready,
      serverRevision: server.revision,
      uiSessionId: ui.sessionId,
      uiSelectedSessionId: ui.selectedSessionId,
      uiReady: ui.ready,
      uiRevision: ui.revision,
      uiRows: ui.memoryCount,
      uiError: ui.error,
      uiLastError: ui.lastError,
      selectedSessionId: db.selectedSessionId,
      sessionFlags: db.sessionFlags,
      // The top-level `:materials/*` keys are the last pushed baseline and
      // legitimately lag the rendered projection; only the projection has to
      // describe the selected session.
      agree: Boolean(ui.sessionId)
        && ui.sessionId === db.selectedSessionId
        && ui.ready === true,
    };
  }).catch((error) => ({ probe: `failed: ${String(error)}` }));
}

// Open the materials page and wait until the rendered projection describes
// the selected session.
//
// A full page load resets the bridge's `fetched-session`, so the first
// refresh after it always goes out; each further `goto` interrupts whatever
// refresh was in flight and starts over.  Retrying by reloading therefore
// never lets a slow snapshot land.  Load once, then poll in place; reload
// only if the whole wait expires.
async function openMaterialsMemory(page, appUrl) {
  const attempt = async (timeout) => {
    await goto(page, appUrl, '/materials');
    // A full page load reconnects the transport; wait for the sync badge
    // before asking for materials, otherwise the snapshot command is refused.
    await waitForSync(page, 30000).catch(() => {});
    await page.getByRole('tab', { name: '长期记忆', exact: true }).first()
      .click({ timeout: 15000 }).catch(() => {});
    await page.locator('.hc-mat-section').first()
      .waitFor({ state: 'visible', timeout: 30000 }).catch(() => {});
    const deadline = Date.now() + timeout;
    let agreement = await materialsAgreement(page);
    while (!agreement.agree && Date.now() < deadline) {
      await page.waitForTimeout(400);
      agreement = await materialsAgreement(page);
    }
    return agreement;
  };
  let agreement = await attempt(90000);
  if (!agreement.agree) {
    agreement = await attempt(60000);
  }
  if (!agreement.agree) {
    throw new Error(
      `materials never settled on the selected session: ${JSON.stringify(agreement)}`);
  }
  return agreement;
}

function memoryCheckbox(page, label) {
  return page.locator('.hc-mat-check', { hasText: label }).locator('input[type="checkbox"]').first();
}

async function setMemorySwitch(page, label, on) {
  const box = memoryCheckbox(page, label);
  await box.waitFor({ state: 'visible', timeout: 15000 });
  if ((await box.isChecked()) !== on) await box.click();
  // The toggle queues a settings command; wait until the checkbox reflects
  // the requested state.
  await page.waitForFunction(([needle, want]) => {
    const labels = [...document.querySelectorAll('.hc-mat-check')];
    const el = labels.find((l) => (l.textContent || '').includes(needle));
    const input = el && el.querySelector('input[type="checkbox"]');
    return input && input.checked === want;
  }, [label, on], { timeout: 30000 });
  await page.waitForTimeout(600);
}

function memoryRows(page) {
  return page.locator('.hc-memory-row');
}

async function rowByText(page, text) {
  const rows = page.locator('.hc-memory-row', { hasText: text });
  await rows.first().waitFor({ state: 'visible', timeout: 20000 });
  return rows.first();
}

function editorPane(page) {
  return page.locator('.hc-mat-editor').first();
}

async function fillEditorField(page, label, value) {
  const field = editorPane(page).locator('.hc-mat-field', { hasText: label }).first();
  const control = field.locator('textarea, input[type="text"], select').first();
  await control.waitFor({ state: 'visible', timeout: 15000 });
  const tag = await control.evaluate((el) => el.tagName.toLowerCase());
  if (tag === 'select') {
    await control.selectOption({ label: value }, { timeout: 15000 });
  } else if (tag === 'textarea') {
    await control.fill(value, { timeout: 15000 });
  } else {
    await control.fill(value, { timeout: 15000 });
  }
  await page.waitForTimeout(250);
}

async function clickEditorButton(page, name) {
  await editorPane(page).getByRole('button', { name, exact: false }).first()
    .click({ timeout: 15000 });
}

async function suggestAndWait(page, mock, timeout = 120000) {
  const taskBefore = (await mockState(mock)).taskCalls;
  await page.getByRole('button', { name: '整理记忆', exact: true }).first()
    .click({ timeout: 15000 });
  // The summary note persists across runs, so waiting for its text alone can
  // return before this click's command reaches the task model.  Wait for the
  // mock's task-call counter to advance, then confirm the note is rendered.
  const deadline = Date.now() + timeout;
  let taskAfter = taskBefore;
  while (Date.now() < deadline) {
    const state = await mockState(mock).catch(() => null);
    taskAfter = state ? state.taskCalls : taskAfter;
    if (taskAfter > taskBefore) break;
    await page.waitForTimeout(200);
  }
  await page.locator('.hc-mat-note', { hasText: '本次整理' }).first()
    .waitFor({ state: 'visible', timeout });
  return { taskBefore, taskAfter };
}

test('@e2e E2E 11 memory candidates, archive and temporary sessions', async ({ page, browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE, { recursive: true });
  const mock = await startE2e11Mock();
  const app = await startApp({
    evidenceDir: EVIDENCE,
    models: {
      connections: [{
        id: 'mock',
        name: 'E2E11 Mock',
        baseUrl: `${mock.url}/v1`,
        models: [
          { id: MAIN_MODEL, displayName: 'Mock Main', maxOutputTokens: 2048, capabilities: ['tools'] },
          { id: TASK_MODEL, displayName: 'Mock Task', maxOutputTokens: 2048, capabilities: ['tools'] },
        ],
      }],
      roles: {
        main: { connection: 'mock', model: MAIN_MODEL },
        task: { connection: 'mock', model: TASK_MODEL },
      },
    },
    nudgeRoundBudget: 1,
  });
  const result = {
    test: 'E2E 11', mode: 'automated', mock: true,
    startedAt: new Date().toISOString(),
    appUrl: app.url, mockUrl: mock.url,
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: (() => {
      try {
        const tree = execSync('git rev-parse HEAD^{tree}', { cwd: REPO_ROOT })
          .toString().trim();
        return crypto.createHash('sha256')
          .update(`${tree}\n`)
          .update(fs.readFileSync(new URL(import.meta.url)))
          .digest('hex');
      } catch { return null; }
    })(),
    treeSha256Dual: sourceTreeSha256(),
    checks: {}, screenshots: [],
  };
  const save = (name) => writeJson(EVIDENCE, name, result);
  const check = (name, ok, detail) => {
    result.checks[name] = { pass: Boolean(ok), detail };
    if (!ok) throw new Error(`check failed: ${name} — ${detail}`);
  };

  try {
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await sendAndSettle(page, mock, 'E2E11 你好，请只回复 OK。', 1);
    check('first-turn-replied',
          (await lastAssistantText(page)).includes('E2E11 mock reply'),
          await lastAssistantText(page));

    // --- defaults off ----------------------------------------------------
    await openMaterialsMemory(page, app.url);
    const readBox = memoryCheckbox(page, '读取已保存记忆');
    const suggestBox = memoryCheckbox(page, '自动建议新记忆');
    check('defaults-off',
          !(await readBox.isChecked()) && !(await suggestBox.isChecked()),
          `read=${await readBox.isChecked()} suggest=${await suggestBox.isChecked()}`);
    const organize = page.getByRole('button', { name: '整理记忆', exact: true }).first();
    check('suggest-button-disabled-by-default',
          await organize.isDisabled(),
          '整理记忆 disabled while 自动建议新记忆 is off');
    result.screenshots.push(await shot(page, EVIDENCE, '01-defaults-off'));

    // --- switches act independently --------------------------------------
    // Read on, suggest stays off.
    await setMemorySwitch(page, '读取已保存记忆', true);
    check('read-switch-on', await readBox.isChecked(), '读取已保存记忆 checked');
    check('suggest-still-off', !(await suggestBox.isChecked()),
          '自动建议新记忆 stays off');
    check('suggest-button-still-disabled', await organize.isDisabled(),
          '整理记忆 disabled after read-only toggle');
    // Suggest on while read stays on: both independent.
    await setMemorySwitch(page, '自动建议新记忆', true);
    check('suggest-switch-on', await suggestBox.isChecked(),
          '自动建议新记忆 checked');
    await page.waitForFunction(() => {
      const btn = [...document.querySelectorAll('button')]
        .find((b) => (b.textContent || '').includes('整理记忆'));
      return btn && !btn.disabled;
    }, null, { timeout: 30000 });
    check('suggest-button-enabled', !(await organize.isDisabled()),
          '整理记忆 enabled after suggest toggle');
    result.screenshots.push(await shot(page, EVIDENCE, '02-switches-on'));

    // --- a manual active memory, then suggest -> candidates ----------------
    // Create one active memory through the editor so injection has content.
    await clickEditorButton(page, '新建');
    await fillEditorField(page, '正文', 'E2E11 手动记忆：用户喜欢简洁回答。');
    await fillEditorField(page, '类别', '表达偏好');
    await clickEditorButton(page, '保存记忆');
    await (await rowByText(page, 'E2E11 手动记忆')).waitFor({ state: 'visible', timeout: 30000 });
    check('manual-memory-active',
          await page.locator('.hc-memory-row', { hasText: 'E2E11 手动记忆' })
            .filter({ hasText: '生效' }).count() === 1,
          'manual memory shows 生效 status');

    // First suggestion run: one candidate lands in 待审.
    const s1 = await suggestAndWait(page, mock);
    check('suggest-ran', s1.taskAfter > s1.taskBefore,
          `task calls ${s1.taskBefore} -> ${s1.taskAfter}`);
    const cand1 = await rowByText(page, 'E2E11 候选甲');
    check('candidate-pending',
          (await cand1.innerText()).includes('待审'),
          (await cand1.innerText()).slice(0, 300));
    result.screenshots.push(await shot(page, EVIDENCE, '03-candidate-pending'));

    // Candidates are not injected: send a turn and inspect the wire body.
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await selectFirstSession(page);
    const mainBeforeCandidate = (await mockState(mock)).mainCalls;
    await sendAndSettle(page, mock, 'E2E11 候选不应注入。', mainBeforeCandidate + 1);
    let state = await mockState(mock);
    const lastMain = await lastMainBody(mock, 'E2E11 候选不应注入。');
    check('candidate-not-injected',
          !lastMain.includes('E2E11 候选甲')
            && !lastMain.includes('E2E11 候选乙'),
          'candidate text absent from the model request');

    // --- review: approve one candidate, reject the other -------------------
    await openMaterialsMemory(page, app.url);
    // Approve 候选甲 (scope stays 全局).
    await (await rowByText(page, 'E2E11 候选甲')).click();
    await clickEditorButton(page, '批准候选');
    await page.locator('.hc-memory-row', { hasText: 'E2E11 候选甲' })
      .filter({ hasText: '生效' }).first()
      .waitFor({ state: 'visible', timeout: 30000 });
    check('candidate-approved',
          await page.locator('.hc-memory-row', { hasText: 'E2E11 候选甲' })
            .filter({ hasText: '生效' }).count() === 1,
          'approved candidate shows 生效');

    // Reject 候选乙 with a reason.
    const s2 = await suggestAndWait(page, mock);
    check('suggest-second-run', s2.taskAfter > s2.taskBefore,
          `task calls ${s2.taskBefore} -> ${s2.taskAfter}`);
    await (await rowByText(page, 'E2E11 候选乙')).click();
    const rejectField = editorPane(page).locator('.hc-mat-field', { hasText: '拒绝原因' })
      .locator('input[type="text"]').first();
    await rejectField.fill('E2E11 测试拒绝原因');
    await clickEditorButton(page, '拒绝');
    await page.locator('.hc-memory-row', { hasText: 'E2E11 候选乙' })
      .filter({ hasText: '已拒绝' }).first()
      .waitFor({ state: 'visible', timeout: 30000 });
    check('candidate-rejected',
          await page.locator('.hc-memory-row', { hasText: 'E2E11 候选乙' })
            .filter({ hasText: '已拒绝' }).count() === 1,
          'rejected candidate shows 已拒绝');
    result.screenshots.push(await shot(page, EVIDENCE, '04-review-done'));

    // --- rescope: approve a third candidate with a changed scope -----------
    const s3 = await suggestAndWait(page, mock);
    check('suggest-third-run', s3.taskAfter > s3.taskBefore,
          `task calls ${s3.taskBefore} -> ${s3.taskAfter}`);
    await (await rowByText(page, 'E2E11 候选丙')).click();
    const scopeField = editorPane(page).locator('.hc-mat-field', {
      has: page.locator('.hc-mat-field-label', { hasText: '范围' }),
    }).first();
    await scopeField.locator('select').first()
      .selectOption({ value: 'project' }, { timeout: 15000 });
    await editorPane(page).locator('.hc-mat-field', {
      has: page.locator('.hc-mat-field-label', { hasText: '项目 ID' }),
    }).first().waitFor({ state: 'visible', timeout: 15000 });
    const projField = editorPane(page).locator('.hc-mat-field', { hasText: '项目 ID' })
      .locator('input[type="text"]').first();
    await projField.fill('e2e11-proj', { timeout: 15000 });
    await clickEditorButton(page, '批准候选');
    const row丙 = page.locator('.hc-memory-row', { hasText: 'E2E11 候选丙' })
      .filter({ hasText: '生效' })
      .filter({ hasText: '项目 e2e11-proj' })
      .first();
    await row丙.waitFor({ state: 'visible', timeout: 30000 });
    const row丙Text = await row丙.innerText();
    check('rescope-approve',
          row丙Text.includes('生效') && row丙Text.includes('项目 e2e11-proj'),
          row丙Text.slice(0, 400));
    result.screenshots.push(await shot(page, EVIDENCE, '05-rescope-approved'));

    // --- injection: approved global memory reaches the model ---------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await selectFirstSession(page);
    const mainBeforeInject = (await mockState(mock)).mainCalls;
    await sendAndSettle(page, mock, 'E2E11 注入验证。', mainBeforeInject + 1);
    state = await mockState(mock);
    const injectBody = await lastMainBody(mock, 'E2E11 注入验证。');
    const memoryBlock = memorySectionOf(injectBody);
    check('memory-injected',
          Boolean(memoryBlock)
            && memoryBlock.includes('E2E11 手动记忆')
            && memoryBlock.includes('E2E11 候选甲'),
          (memoryBlock || 'no Memory block').slice(0, 500));
    check('project-scoped-not-injected',
          !(memoryBlock || '').includes('E2E11 候选丙'),
          'project-scoped memory absent from a project-less session');
    result.screenshots.push(await shot(page, EVIDENCE, '06-injection'));

    // --- archive stops injection -------------------------------------------
    await openMaterialsMemory(page, app.url);
    await (await rowByText(page, 'E2E11 手动记忆')).click();
    await clickEditorButton(page, '归档');
    await page.locator('.hc-memory-row', { hasText: 'E2E11 手动记忆' })
      .filter({ hasText: '已归档' }).first()
      .waitFor({ state: 'visible', timeout: 30000 });
    check('memory-archived',
          await page.locator('.hc-memory-row', { hasText: 'E2E11 手动记忆' })
            .filter({ hasText: '已归档' }).count() === 1,
          'archived memory shows 已归档');

    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await selectFirstSession(page);
    const mainBeforeArchive = (await mockState(mock)).mainCalls;
    await sendAndSettle(page, mock, 'E2E11 归档后验证。', mainBeforeArchive + 1);
    state = await mockState(mock);
    const archiveBody = await lastMainBody(mock, 'E2E11 归档后验证。');
    const archiveBlock = memorySectionOf(archiveBody);
    check('archive-stops-injection',
          !(archiveBlock || '').includes('E2E11 手动记忆')
            && (archiveBlock || '').includes('E2E11 候选甲'),
          (archiveBlock || 'no Memory block').slice(0, 500));
    result.screenshots.push(await shot(page, EVIDENCE, '07-archive'));

    // --- temporary session --------------------------------------------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await page.locator('button[aria-label="新建临时会话"]').first().click();
    await composer(page).waitFor({ state: 'visible', timeout: 15000 });
    await page.waitForTimeout(1000);

    // The materials page for a temporary session shows the warning and an
    // empty memory table.
    await openMaterialsMemory(page, app.url);
    const tempNoteLoc = page.locator('.hc-mat-note', { hasText: '临时会话' }).first();
    const tempNote = (await tempNoteLoc.count()) === 0
      ? '' : await tempNoteLoc.innerText().catch(() => '');
    check('temp-warning',
          tempNote.includes('临时会话') && tempNote.includes('不读不写'),
          tempNote.slice(0, 300));
    check('temp-memory-table-empty',
          (await memoryRows(page).count()) === 0,
          `temporary session shows ${await memoryRows(page).count()} memory rows`);
    const tempReadBox = page.locator('.hc-mat-check', { hasText: '仅本次读取已保存记忆' })
      .locator('input[type="checkbox"]').first();
    check('temp-read-control-present',
          (await tempReadBox.count()) === 1,
          'temporary read checkbox rendered');
    check('temp-read-off-by-default',
          !(await tempReadBox.isChecked()),
          'temporary read starts off');
    const tempScopeLoc = page.locator('.hc-mat-warning', { hasText: '范围：' }).first();
    const tempScope = (await tempScopeLoc.count()) === 0
      ? '' : await tempScopeLoc.innerText().catch(() => '');
    check('temp-read-scope',
          tempScope.includes('会话') && tempScope.includes('助手') && tempScope.includes('项目'),
          tempScope.slice(0, 300));
    result.screenshots.push(await shot(page, EVIDENCE, '08-temp-session'));

    // Server refuses memory commands from a temporary session: attempt a
    // save through the editor (it will be refused) and confirm no row lands.
    await clickEditorButton(page, '新建');
    await fillEditorField(page, '正文', 'E2E11 临时会话写入尝试。');
    await clickEditorButton(page, '保存记忆');
    await page.waitForTimeout(2500);
    const tempErrorText = await page.locator('.hc-mat-section')
      .locator('.wb-error, .hc-mat-error')
      .allInnerTexts()
      .catch(() => []);
    check('temp-write-refused',
          (await memoryRows(page).count()) === 0
            && tempErrorText.some((t) => /临时会话|不读不写|拒绝/.test(t)),
          `rows=${await memoryRows(page).count()} errors=${JSON.stringify(tempErrorText)}`);

    // One-shot read: enable it, send, verify injection, then send again
    // and verify the read did not persist.
    await tempReadBox.click();
    await page.waitForFunction(() => {
      const el = [...document.querySelectorAll('.hc-mat-check')]
        .find((l) => (l.textContent || '').includes('仅本次读取已保存记忆'));
      const input = el && el.querySelector('input[type="checkbox"]');
      return input && input.checked === true;
    }, null, { timeout: 15000 });
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    // Stay on the temporary session (it is the selected one).
    const mainBeforeTempRead = (await mockState(mock)).mainCalls;
    await sendAndSettle(page, mock, 'E2E11 临时会话一次性读取。', mainBeforeTempRead + 1);
    state = await mockState(mock);
    // The temporary-read send produces a context call plus a tool
    // continuation; the continuation has more messages but no Memory block.
    // Check every main request for this marker instead of taking the largest.
    const tempReadState = await mockState(mock);
    const tempReadBodies = (tempReadState.bodies || [])
      .filter((raw, i) => {
        const req = (tempReadState.log || [])[i];
        return req && req.model === MAIN_MODEL
          && requestHasUserText(raw, 'E2E11 临时会话一次性读取。');
      });
    const tempBlock = tempReadBodies
      .map((raw) => memorySectionOf(raw))
      .find((block) => block && block.includes('E2E11 候选甲'));
    check('temp-read-injected',
          Boolean(tempBlock) && tempBlock.includes('E2E11 候选甲'),
          (tempBlock || 'no Memory block').slice(0, 500));

    // The one-shot read must not persist: the next send has no Memory block
    // for the temporary session even though memory-read? is still on.
    const mainBeforeTempSecond = (await mockState(mock)).mainCalls;
    await sendAndSettle(page, mock, 'E2E11 临时会话第二轮。', mainBeforeTempSecond + 1);
    state = await mockState(mock);
    const tempSecondState = await mockState(mock);
    const tempSecondBodies = (tempSecondState.bodies || [])
      .filter((raw, i) => {
        const req = (tempSecondState.log || [])[i];
        return req && req.model === MAIN_MODEL
          && requestHasUserText(raw, 'E2E11 临时会话第二轮。');
      });
    const tempSecondBlock = tempSecondBodies
      .map((raw) => memorySectionOf(raw))
      .find((block) => block && block.includes('E2E11 候选甲'));
    check('temp-read-not-persistent',
          !(tempSecondBlock || '').includes('E2E11 候选甲'),
          (tempSecondBlock || 'no Memory block').slice(0, 500));
    result.screenshots.push(await shot(page, EVIDENCE, '09-temp-read'));

    // Switching back to the normal session still shows the approved memory
    // and the temporary session wrote nothing.  Switch on the sessions
    // route first — the materials snapshot is scoped to the selected
    // session.
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    // Pick the first non-temporary session row explicitly.
    const sessionRows = page.locator('.wb-session-list .wb-list-button');
    const rowCount = await sessionRows.count();
    let picked = false;
    for (let i = 0; i < rowCount; i += 1) {
      const text = await sessionRows.nth(i).innerText();
      if (!text.includes('临时')) {
        await sessionRows.nth(i).click();
        picked = true;
        break;
      }
    }
    check('normal-session-reselected', picked, 'selected a non-temporary session');
    await page.waitForTimeout(1500);
    await openMaterialsMemory(page, app.url);
    // openMaterialsMemory already retried through reconnects and asserted the
    // ready badge; read the same visible locator immediately below so hidden
    // stale rows cannot satisfy the count.
    const readyBadge = page.locator('.hc-mat-badge')
      .filter({ hasText: '材料库已就绪' }).first();
    const badgeText = await readyBadge.innerText();
    result.screenshots.push(
      await shot(page, EVIDENCE, '10a-normal-session-ready'));
    const shellFacts = await page.evaluate(() => {
      const facts = {};
      document.querySelectorAll('.wb-facts').forEach((group) => {
        [...group.querySelectorAll('dt')].forEach((dt) => {
          const dd = dt.nextElementSibling;
          if (dd) facts[dt.textContent.trim()] = dd.textContent.trim();
        });
      });
      return facts;
    });
    const visibleRows = await page.locator('.hc-memory-row:visible').count();
    const materialRevision = shellFacts['材料 revision'] ?? '—';
    const syncSeq = shellFacts['同步序号'] ?? '—';
    // Server keys and the rendered projection are separate stores; a ready
    // badge with zero rows means they disagree.  Dump both so the next
    // failure names the store instead of the symptom.
    const materialsDiag = await page.evaluate(() => {
      const probe = window.__HC_DB__;
      if (typeof probe !== 'function') return { probe: 'absent' };
      const snapshot = probe();
      return snapshot && snapshot.materials ? snapshot.materials : { materials: 'absent' };
    }).catch((error) => ({ probe: `failed: ${String(error)}` }));
    const detail = `badge=${badgeText}; revision=${materialRevision}; `
      + `seq=${syncSeq}; visibleRows=${visibleRows}; `
      + `materials=${JSON.stringify(materialsDiag)}`;
    check('normal-session-memory-visible',
          visibleRows >= 1 && badgeText.includes('材料库已就绪'),
          detail);
    result.screenshots.push(
      await shot(page, EVIDENCE, '10b-normal-session-rows'));

    result.ok = Object.values(result.checks).every((c) => c.pass);
    result.finishedAt = new Date().toISOString();
    result.mockFinal = await mockState(mock).catch(() => null);
    save('e2e-11-result');
    assert(result.ok, 'one or more E2E 11 checks failed');
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockFinal = await mockState(mock).catch(() => null);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e-11-error').catch(() => null));
    save('e2e-11-result');
    throw error;
  } finally {
    await app.close();
    await mock.close();
  }
});
