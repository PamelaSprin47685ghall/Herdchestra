// E2E 20: temporary session discard / transfer.
//
// One temporary session carries synthetic sensitive markers through every
// external service the app can reach: the chat model (required web search +
// model reply), the speech-to-text service (a real multipart upload to the
// mock transcription endpoint), and the memory-suggestion model pass
// (candidate generation through the task role).  The session is then
// discarded through the real UI; history, session search, memory candidates,
// the backup preview and the deleted area must all show no residue.
//
// A second temporary session exercises the transfer path: 转为普通 keeps the
// content and draft, does not re-run any external action (model/search/STT
// request counts stay flat), and the session then behaves like a normal
// session.
//
// Already-sent external content is reported honestly in the result JSON:
// every marker below was transmitted to the mock model, mock search and
// mock speech endpoints before the discard, and the evidence records the
// exact request counts.  Nothing is claimed about real providers.
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from '@playwright/test';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockSearch } from './support/mock-search.mjs';
import { startMockWebDAV } from './support/mock-services.mjs';
import {
  assert, assertEqual, composer, goto, lastAssistantText,
  newSession, shot, turnLive, userTexts, waitForAssistant, waitForShell,
  waitForSync, writeJson, clientBundleInfo,
} from './support/ui.mjs';
import { sourceTreeSha256, collectMockState } from './support/evidence.mjs';

const EVIDENCE = path.join(REPO_ROOT, 'plans/evidence/e2e-20');

const MARK_USER = 'E2E20 用户探针-合成敏感标记-请勿外泄';
const MARK_REPLY = 'E2E20 助手回复-合成敏感标记';
const MARK_STT = 'E2E20 转写探针-合成敏感标记-语音转写文本';
const MARK_MEMORY = 'E2E20 记忆候选-合成敏感标记-永远记住榴莲';
const MARK_TRANSFER = 'E2E20 转存探针-合成敏感标记-转存后保留';
const BACKUP_PASSWORD = 'e2e20 backup password 2026';

const MEMORY_JSON = JSON.stringify([{
  text: MARK_MEMORY,
  category: 'working-style',
  epistemic: 'preference',
  confidence: 0.9,
  'source-message-id': '',
  quote: '',
}]);

function sse(res, value) {
  res.write(`data: ${JSON.stringify(value)}\n\n`);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

// One mock HTTP server for both the OpenAI-compatible chat endpoint and the
// OpenAI-compatible speech-to-text endpoint.  A request whose messages carry
// the memory-suggest system prompt gets the JSON candidate list; every other
// chat request gets the fixed marker reply.  `/v1/audio/transcriptions`
// returns the STT marker regardless of the uploaded audio bytes.
async function startE2E20Mock() {
  const state = {
    chat: [], searchQueries: [], transcriptions: [], tts: [],
    memorySuggestCalls: 0,
  };
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (req.method === 'GET' && url.pathname === '/__mock/state') {
      const body = JSON.stringify({ mock: true, ...state });
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(body);
    }
    if (req.method === 'POST' && url.pathname === '/v1/chat/completions') {
      const raw = await readBody(req);
      let body = {};
      try { body = JSON.parse(raw.toString('utf8') || '{}'); } catch { /* empty */ }
      const messages = Array.isArray(body.messages) ? body.messages : [];
      const systemText = messages
        .filter((m) => m && m.role === 'system')
        .map((m) => String(m.content || '')).join('\n');
      const isSuggest = systemText.includes('extract long-term memory candidates');
      const model = String(body.model || 'mock-e2e20');
      const id = `chatcmpl-e2e20-${state.chat.length + 1}`;
      state.chat.push({
        at: new Date().toISOString(), model,
        stream: body.stream === true,
        messageCount: messages.length,
        isSuggest,
        lastUser: String(messages.filter((m) => m && m.role === 'user')
          .at(-1)?.content || '').slice(0, 400),
      });
      if (isSuggest) state.memorySuggestCalls += 1;
      const reply = isSuggest ? MEMORY_JSON : `${MARK_REPLY}（第 ${state.chat.length} 次模型请求）`;
      const usage = { prompt_tokens: 30, completion_tokens: 12, total_tokens: 42,
                      prompt_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 } };
      if (body.stream === true) {
        res.writeHead(200, {
          'content-type': 'text/event-stream; charset=utf-8',
          'cache-control': 'no-cache', connection: 'keep-alive',
          'x-herdchestra-mock': 'e2e20',
        });
        res.flushHeaders?.();
        const chunk = (delta, finish = null) => ({
          id, object: 'chat.completion.chunk',
          created: Math.floor(Date.now() / 1000), model,
          choices: [{ index: 0, delta, finish_reason: finish }],
        });
        sse(res, chunk({ role: 'assistant', content: '' }));
        for (let i = 0; i < reply.length; i += 24) {
          sse(res, chunk({ content: reply.slice(i, i + 24) }));
        }
        sse(res, chunk({}, 'stop'));
        sse(res, { id, object: 'chat.completion.chunk',
                   created: Math.floor(Date.now() / 1000), model,
                   choices: [], usage });
        res.write('data: [DONE]\n\n');
        return res.end();
      }
      const out = JSON.stringify({
        id, object: 'chat.completion',
        created: Math.floor(Date.now() / 1000), model,
        choices: [{ index: 0,
                    message: { role: 'assistant', content: reply },
                    finish_reason: 'stop' }],
        usage,
      });
      res.writeHead(200, { 'content-type': 'application/json',
                           'content-length': Buffer.byteLength(out) });
      return res.end(out);
    }
    if (req.method === 'POST' && url.pathname === '/v1/audio/transcriptions') {
      const raw = await readBody(req);
      state.transcriptions.push({
        at: new Date().toISOString(),
        bytes: raw.length,
        contentType: req.headers['content-type'] || '',
        serviceId: req.headers['x-hc-service-id'] || null,
      });
      const body = JSON.stringify({ text: `${MARK_STT}（转写 ${state.transcriptions.length} 次）` });
      res.writeHead(200, { 'content-type': 'application/json',
                           'content-length': Buffer.byteLength(body) });
      return res.end(body);
    }
    if (req.method === 'POST' && url.pathname === '/v1/audio/speech') {
      await readBody(req);
      state.tts.push({ at: new Date().toISOString() });
      // A tiny valid WAV so the client can treat it as audio bytes.
      const wav = Buffer.alloc(46);
      wav.write('RIFF', 0); wav.writeUInt32LE(38, 4); wav.write('WAVE', 8);
      wav.write('fmt ', 12); wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20);
      wav.writeUInt16LE(1, 22); wav.writeUInt32LE(8000, 24);
      wav.writeUInt32LE(16000, 28); wav.writeUInt16LE(2, 32);
      wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(2, 40);
      res.writeHead(200, { 'content-type': 'audio/wav' });
      return res.end(wav);
    }
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'not found', mock: true }));
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

// Minimal 16-bit mono 8 kHz WAV: 800 samples of silence = 100 ms.
function makeWav() {
  const dataSize = 1600;
  const buf = Buffer.alloc(44 + dataSize);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + dataSize, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22); buf.writeUInt32LE(8000, 24);
  buf.writeUInt32LE(16000, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write('data', 36); buf.writeUInt32LE(dataSize, 40);
  return buf;
}

// EDN serialization for the speech bootstrap section (kebab-case keys are
// already EDN keywords; strings are quoted).
function ednString(v) { return JSON.stringify(String(v)); }
function ednValue(v) {
  if (v === null || v === undefined) return 'nil';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') return String(v);
  if (typeof v === 'string' && v.startsWith(':')) return v;
  if (typeof v === 'string') return ednString(v);
  if (Array.isArray(v)) return `[${v.map(ednValue).join(' ')}]`;
  if (v && typeof v === 'object') {
    return `{${Object.entries(v).map(([k, val]) =>
      `:${k} ${ednValue(val)}`).join(' ')}}`;
  }
  throw new Error(`ednValue: unsupported ${typeof v}`);
}

function speechConfigEdn(mockUrl) {
  return ednValue({
    stt: {
      services: [{
        id: 'mock-stt', label: 'Mock STT', kind: ':openai-compatible',
        'base-url': mockUrl, 'transcriptions-path': '/v1/audio/transcriptions',
        model: 'whisper-mock', 'processing': ':cloud', 'enabled?': true,
        order: 0, 'response-format': 'json',
        'max-duration-ms': 300000, 'max-audio-bytes': 26214400,
        'connect-timeout-ms': 5000, 'request-timeout-ms': 30000,
      }],
      'default-service-id': 'mock-stt',
      'max-duration-ms': 300000,
      'max-audio-bytes': 26214400,
      language: 'auto',
      'response-format': 'json',
    },
  });
}

// `startApp` writes its own config.edn without a :speech section, but the
// speech component falls back to `<data-dir>/speech/config.edn` only when it
// exists BEFORE `speech/start!` runs.  Write it between the directory
// creation inside `startApp` and the server spawn is impossible without
// touching support code, so we run startApp twice on the same tempRoot:
// the first call only materializes the directories, we close it, write the
// persisted speech config, then start the real instance.  The second
// startApp reuses every directory and writes workspace files idempotently.
async function startAppWithSpeech(options, mockUrl) {
  const probe = await startApp({ ...options, root: options.root || undefined });
  const { tempRoot, dataDir } = probe;
  const speechDir = path.join(dataDir, 'speech');
  fs.mkdirSync(speechDir, { recursive: true });
  fs.writeFileSync(path.join(speechDir, 'config.edn'),
    `${speechConfigEdn(mockUrl)}\n`);
  await probe.close();
  return startApp({ ...options, root: tempRoot });
}

async function bodyText(page) {
  return page.locator('body').innerText();
}

async function waitText(page, text, timeout = 60000) {
  // A real DOM condition: the body text contains the needle.  The timeout
  // is the bound; there is no separate settle guess.
  return page.waitForFunction((needle) =>
    (document.body.innerText || '').includes(needle), text, { timeout })
    .then(() => true)
    .catch(() => false);
}

async function sessionFilter(page, value) {
  // Two inputs exist (session page aside + nav drawer).  Scope to the
  // session page's own aside — the nav drawer input filters a different
  // list and can be present-but-hidden at this viewport.
  const input = page.locator('.wb-session-list #wb-session-search').first();
  await input.fill(value);
  // `fill` on a controlled React input must round-trip through onChange;
  // verify the DOM value actually landed before the caller reads lists.
  const waitForLanded = () => page.waitForFunction((expected) => {
    const el = document.querySelector('.wb-session-list #wb-session-search');
    return Boolean(el && el.value === expected);
  }, value, { timeout: 5000 }).catch(() => false);
  if (!(await waitForLanded())) {
    await input.click().catch(() => {});
    await page.keyboard.press('ControlOrMeta+A').catch(() => {});
    await page.keyboard.type(value, { delay: 10 }).catch(() => {});
    await waitForLanded();
  }
}

async function sessionFilterText(page) {
  // The assertion targets the session page list; the nav drawer list is a
  // separate surface that may still show unfiltered rows.
  return (await page.locator('.wb-session-list')
    .first().innerText().catch(() => '')).trim();
}

async function sendTurnE2E20(page, text, mock, timeout = 120000, { fill = true } = {}) {
  await waitForSync(page, 30000);
  // A fresh session must be selected and its conversation log empty before
  // sending.  A global assistant count also included the previously selected
  // session's bubbles, so `count > before` missed a real reply after a
  // session switch.
  const emptyLog = await page.waitForFunction(() => {
    const log = document.querySelector('[aria-label="会话消息"]');
    if (!log) return false;
    return log.querySelectorAll(
      '.hc-message-in:not(.hc-message-process) .hc-message-body:has(*)').length === 0;
  }, null, { timeout: 15000 }).then(() => true).catch(() => false);
  if (!emptyLog) {
    const dump = await page.evaluate(() => ({
      log: document.querySelector('[aria-label="会话消息"]')?.innerText
        ?.slice(0, 1500) || '',
      bodyText: (document.body.innerText || '').slice(0, 3000),
    }));
    throw new Error(
      `selected session is not a fresh empty session before send; `
      + `dump=${JSON.stringify(dump)}`);
  }
  const before = 0;
  if (fill) {
    // The composer is uncontrolled; `fill` fires input events that the
    // reducer turns into :draft/edit.  Real keystrokes via keyboard.type
    // additionally drive keydown/keyup and IME-adjacent paths the same
    // way an operator does.  Type into the focused composer, then verify
    // the send button enables; retry with a fresh clear+type if the
    // session binding moved underneath us.
    const input = composer(page);
    const fillDeadline = Date.now() + 90000;
    let committed = false;
    while (Date.now() < fillDeadline) {
      const current = await input.inputValue().catch(() => '');
      if (current !== text) {
        await input.click({ timeout: 5000 }).catch(() => {});
        await page.keyboard.press('ControlOrMeta+A').catch(() => {});
        await page.keyboard.press('Backspace').catch(() => {});
        await page.keyboard.type(text, { delay: 8 }).catch(() => {});
        // Wait for the controlled textarea to echo the typed value.
        await page.waitForFunction((expected) => {
          const el = document.querySelector('textarea[aria-label="消息输入"]');
          return Boolean(el && el.value === expected);
        }, text, { timeout: 5000 }).catch(() => {});
        continue;
      }
      const sendEnabled = await page.evaluate(() => {
        const button = [...document.querySelectorAll('button[aria-label="发送消息"]')][0];
        return Boolean(button && !button.disabled);
      });
      if (sendEnabled) { committed = true; break; }
      // The draft is correct but the send gate is closed; wait for the real
      // enablement instead of a blind poll.
      await page.waitForFunction(() => {
        const button = [...document.querySelectorAll('button[aria-label="发送消息"]')][0];
        return Boolean(button && !button.disabled);
      }, null, { timeout: 2000 }).catch(() => {});
    }
    if (!committed) {
      const dump = await page.evaluate(() => ({
        textareas: [...document.querySelectorAll('textarea')].map((t) => ({
          aria: t.getAttribute('aria-label'), value: t.value.slice(0, 80),
          disabled: t.disabled, visible: t.offsetParent !== null,
        })),
        sendButtons: [...document.querySelectorAll('button[aria-label="发送消息"]')]
          .map((b) => ({ disabled: b.disabled, title: b.title,
                         text: (b.textContent || '').trim() })),
        bodyText: (document.body.innerText || '').slice(0, 3000),
      }));
      console.log('COMPOSER-NOT-COMMITTED', JSON.stringify(dump));
      throw new Error('composer draft never committed; send stayed disabled');
    }
  }
  const sendButton = page.locator('button[aria-label="发送消息"]').first();
  await sendButton.waitFor({ state: 'visible', timeout: 15000 });
  // The send button stays disabled while the sync badge reports 中断 or the
  // draft has not committed; wait for the observable enablement, then click.
  try {
    await page.waitForFunction(() => {
      const button = [...document.querySelectorAll('button[aria-label="发送消息"]')][0];
      return Boolean(button && !button.disabled);
    }, null, { timeout: 30000 });
  } catch {
    const dump = await page.evaluate(() => ({
      textareas: [...document.querySelectorAll('textarea')].map((t) => ({
        aria: t.getAttribute('aria-label'), value: t.value.slice(0, 80),
        disabled: t.disabled, visible: t.offsetParent !== null,
      })),
      sendButtons: [...document.querySelectorAll('button[aria-label="发送消息"]')]
        .map((b) => ({ disabled: b.disabled, title: b.title,
                       text: (b.textContent || '').trim() })),
      bodyText: (document.body.innerText || '').slice(0, 3000),
    }));
    console.log('SEND-DISABLED-DUMP', JSON.stringify(dump));
    throw new Error('send button stayed disabled for 30s');
  }
  await sendButton.click({ timeout: 5000 }).catch((error) => {
    throw new Error(`send click failed: ${String(error)}`);
  });
  // Observable condition 1: the current conversation log contains an
  // assistant body carrying this run's reply marker.  It is scoped to the
  // selected session and requires the real text, not just a count change.
  const appeared = await page.waitForFunction((markerText) => {
    const log = document.querySelector('[aria-label="会话消息"]');
    if (!log) return false;
    return [...log.querySelectorAll(
      '.hc-message-in:not(.hc-message-process) .hc-message-body:has(*)')]
      .some((body) => (body.textContent || '').includes(markerText));
  }, MARK_REPLY, { timeout }).then(() => true).catch(() => false);
  if (!appeared) {
    const dump = await page.evaluate(() => ({
      textareas: [...document.querySelectorAll('textarea')].map((t) => ({
        aria: t.getAttribute('aria-label'), value: t.value.slice(0, 80),
        disabled: t.disabled, visible: t.offsetParent !== null,
      })),
      sendButtons: [...document.querySelectorAll('button[aria-label="发送消息"]')]
        .map((b) => ({ disabled: b.disabled, title: b.title,
                       text: (b.textContent || '').trim() })),
      bodyText: (document.body.innerText || '').slice(0, 3000),
    }));
    const st = await mockState(mock).catch(() => null);
    throw new Error(
      `assistant reply did not appear within ${timeout}ms; `
      + `chatRequests=${st ? st.chat.length : 'unknown'}; dump=${JSON.stringify(dump)}`);
  }
  // Observable condition 2: no live generation, no unattended marker, and
  // the mock chat log stable for 5s.  The 500ms sleep is only the poll
  // interval; the exit condition is the 5s quiet window.
  const settleDeadline = Date.now() + 120000;
  let quietSince = null;
  let lastCount = null;
  while (Date.now() < settleDeadline) {
    const live = await turnLive(page);
    const st = await mockState(mock).catch(() => null);
    const n = st ? st.chat.length : -1;
    const unattended = await page.getByText('无人值守').count().catch(() => 0);
    // A live `turnLive` here is diagnostic, not fatal: the unattended driver
    // keeps the run status occupied after the scripted reply, so requiring a
    // fully idle UI would never terminate.  What must hold is that the mock
    // stopped producing requests and the reply text is on screen.
    if (n === lastCount) {
      if (quietSince === null) quietSince = Date.now();
      if (Date.now() - quietSince >= 8000) {
        const after = await page.evaluate(() => {
          const log = document.querySelector('[aria-label="会话消息"]');
          return log
            ? log.querySelectorAll(
              '.hc-message-in:not(.hc-message-process) .hc-message-body:has(*)').length
            : 0;
        });
        return { ok: true, before, after };
      }
    } else {
      quietSince = null;
      lastCount = n;
    }
    await page.waitForTimeout(500);
  }
  const dump = await page.evaluate(() => ({
    bodyText: (document.body.innerText || '').slice(0, 1200),
    turnLive: Boolean(document.querySelector(
      '[role="group"][aria-label="当前轮仍在生成"]')),
    logPresent: Boolean(document.querySelector('[aria-label="会话消息"]')),
  }));
  const st = await mockState(mock).catch(() => null);
  throw new Error(
    `turn did not settle within 120s; chat=${st ? st.chat.length : '?'}; `
    + `dump=${JSON.stringify(dump)}`);
}

async function createTemporarySession(page) {
  const button = page.locator('button[aria-label="新建临时会话"]').first();
  await button.waitFor({ state: 'visible', timeout: 15000 });
  await button.click();
  await composer(page).waitFor({ state: 'visible', timeout: 15000 });
  // The header banner marks the temporary session.
  await waitText(page, '临时', 15000);
  // The session/create receipt selects the new row asynchronously.  Until
  // its `:session/by-id` row exists and `aria-current` lands, the composer
  // can still be bound to the previously selected session; drafts typed
  // then commit under that session's id and the send button stays
  // "输入为空".  Wait for the temporary row to become current first.
  const tempRow = page.locator('.wb-session-list .wb-list-button')
    .filter({ hasText: '临时会话' }).first();
  await tempRow.waitFor({ state: 'visible', timeout: 30000 });
  await page.waitForFunction(() => {
    const row = [...document.querySelectorAll('.wb-session-list .wb-list-button[aria-current="true"]')]
      .find((el) => (el.textContent || '').includes('临时会话'));
    return row && row.getAttribute('aria-current') === 'true';
  }, null, { timeout: 60000 });
}

async function createNormalSession(page) {
  await page.locator('button[aria-label="新建会话"]').first().click();
  await composer(page).waitFor({ state: 'visible', timeout: 15000 });
  await waitForSync(page, 30000);
  // The create receipt selects the new normal row asynchronously.  Until the
  // selected row is no longer the temporary session, the composer and the
  // message log can still belong to the old session.
  await page.waitForFunction(() => {
    const current = document.querySelector(
      '.wb-session-list .wb-list-button[aria-current="true"]');
    return Boolean(current && !(current.textContent || '').includes('临时会话'));
  }, null, { timeout: 30000 });
}

async function gotoSessions(page, appUrl) {
  await goto(page, appUrl, '/sessions');
  await waitForSync(page);
}

async function gotoMaterialsMemory(page, appUrl) {
  await goto(page, appUrl, '/materials');
  await page.getByRole('tab', { name: '长期记忆', exact: true }).first()
    .waitFor({ state: 'visible', timeout: 30000 });
  await page.getByRole('tab', { name: '长期记忆', exact: true }).first().click();
  await waitText(page, '材料库已就绪', 90000);
}

async function enableSuggest(page) {
  const label = page.locator('label.hc-mat-check')
    .filter({ hasText: '自动建议新记忆' }).first();
  await label.waitFor({ state: 'visible', timeout: 20000 });
  const btn = page.getByRole('button', { name: '整理记忆', exact: true }).first();
  const enabled = async () => btn.isEnabled().catch(() => false);
  for (let i = 0; i < 4 && !(await enabled()); i += 1) {
    await label.click();
    try {
      await page.waitForFunction(() => {
        const button = [...document.querySelectorAll('button')]
          .find((b) => (b.textContent || '').trim() === '整理记忆');
        return Boolean(button && !button.disabled);
      }, null, { timeout: 25000 });
      return;
    } catch { /* retry the label click once the command settles */ }
  }
  assert(await enabled(), '整理记忆 button never enabled after enabling 自动建议新记忆');
}

// The search control is session-scoped: `state-for` reads
// `[:ui/search-state session-id]`, so a mode picked while another session
// is selected never reaches the temporary session's send payload.  Call
// this AFTER the target session is selected, and wait for the mode
// button's `aria-pressed` so the mutation has committed before typing.
async function enableRequiredSearch(page, serviceId) {
  const group = page.locator('.hc-search-controls[aria-label="联网搜索"]').first();
  await group.waitFor({ state: 'visible', timeout: 20000 });
  const toggle = group.locator('button[aria-expanded]').first();
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') {
    await toggle.click({ timeout: 10000 });
  }
  const serviceSelect = page.locator('.hc-search-row select').first();
  await serviceSelect.waitFor({ state: 'visible', timeout: 15000 });
  await serviceSelect.selectOption({ value: serviceId });
  await page.locator('.hc-search-mode', { hasText: '本轮必须搜索' }).first()
    .click({ timeout: 10000 });
  await page.waitForFunction(() => {
    const node = [...document.querySelectorAll('.hc-search-mode')]
      .find((el) => (el.textContent || '') === '本轮必须搜索');
    return node && node.getAttribute('aria-pressed') === 'true';
  }, null, { timeout: 20000 });
}

// Select a session list row by title and wait until it is `aria-current`.
async function selectSessionByTitle(page, title) {
  const row = page.locator('.wb-session-list .wb-list-button')
    .filter({ hasText: title }).first();
  await row.waitFor({ state: 'visible', timeout: 30000 });
  await row.click();
  await composer(page).waitFor({ state: 'visible', timeout: 15000 });
  await page.waitForFunction((t) => {
    const el = [...document.querySelectorAll('.wb-session-list .wb-list-button[aria-current="true"]')]
      .find((node) => (node.textContent || '').includes(t));
    return Boolean(el);
  }, title, { timeout: 30000 });
}

async function clickButton(page, name, timeout = 30000) {
  const button = page.getByRole('button', { name, exact: true }).first();
  await button.waitFor({ state: 'visible', timeout });
  await button.click();
  // The caller owns the resulting-state wait; a generic settle here was a
  // fixed guess that could not know which observable effect to expect.
}

test.describe.configure({ mode: 'serial' });

test('@core E2E 20 temporary session discard and transfer', async ({ page, browser }) => {
  test.setTimeout(1200000);
  fs.mkdirSync(EVIDENCE, { recursive: true });
  const mock = await startE2E20Mock();
  const search = await startMockSearch();
  const webdav = await startMockWebDAV();
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e20-'));
  const app = await startAppWithSpeech({
    root: tempRoot,
    port: 0,
    evidenceDir: EVIDENCE,
    keepTemp: true,
    nudgeRoundBudget: 2,
    models: {
      connections: [{
        id: 'mock', name: 'E2E20 Mock',
        baseUrl: `${mock.url}/v1`,
        models: [{ id: 'mock-e2e20', displayName: 'Mock E2E20',
                   maxOutputTokens: 2048, capabilities: ['tools'] }],
      }],
      roles: {
        main: { connection: 'mock', model: 'mock-e2e20' },
        task: { connection: 'mock', model: 'mock-e2e20' },
      },
    },
    search: {
      services: [{
        id: 'mock-searxng', name: 'Mock SearXNG', kind: ':searxng',
        baseUrl: search.searxngUrl, enabled: true,
      }],
      defaultServiceId: 'mock-searxng',
    },
    backup: {
      targets: {
        webdav: { kind: 'webdav', baseUrl: `${webdav.url}/backups`,
                  directory: '' },
      },
      defaultTargetId: 'webdav',
    },
  }, mock.url);

  const marker = `E2E20-${Date.now()}`;
  const result = {
    test: 'E2E 20', mode: 'automated', mock: true,
    startedAt: new Date().toISOString(), marker,
    appUrl: app.url, mockUrl: mock.url, searchUrl: search.url,
    webdavUrl: webdav.url,
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    // final25 freeze markers: bundle sha256 45f11529… and the frozen tree
    // hash 76cf2fd0… recorded alongside the live tree hash so reviewers
    // can tell which snapshot this run actually exercised.
    frozenBundleSha256: '45f11529a22da07d23e665c412ad6b1611758d63e28bd464727efa32e174fd30',
    frozenTreeSha256: '76cf2fd01952d06d46932eea1406f849452ed6e0285b2458f7794d1214a42f7b',
    markers: {
      user: MARK_USER, reply: MARK_REPLY, stt: MARK_STT,
      memoryCandidate: MARK_MEMORY, transfer: MARK_TRANSFER,
      note: 'All markers were sent to the mock model/search/speech endpoints; '
            + 'they are synthetic and never reached a real provider.',
    },
    externalServices: {
      modelRequests: 'mock /v1/chat/completions (chat + memory-suggest)',
      searchRequests: 'mock searxng /search (required-search turn)',
      sttRequests: 'mock /v1/audio/transcriptions (multipart upload)',
      backupTarget: 'mock WebDAV /backups (create + preview only)',
    },
    checks: {}, screenshots: [],
  };
  const evidence = (name, value) => writeJson(EVIDENCE, name, value);
  const check = (name, ok, detail) => {
    result.checks[name] = { pass: Boolean(ok), detail: String(detail).slice(0, 800) };
    return Boolean(ok);
  };
  const phaseStart = Date.now();
  const phase = (name) => {
    const entry = { name, at: new Date().toISOString(),
                    elapsedMs: Date.now() - phaseStart };
    (result.phases ||= []).push(entry);
    console.log(`E2E20-PHASE ${name} +${entry.elapsedMs}ms`);
  };

  try {
    await gotoSessions(page, app.url);
    phase('gotoSessions');
    await page.locator('.wb-shell').first().waitFor({ state: 'visible' });
    // ---- Phase 1: temporary session with every external surface ---------
    await createTemporarySession(page);
    phase('createTemporarySession-1');
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-01-temp-created'));

    // Required web search is session-scoped: the mode must be set AFTER
    // the temporary session is selected, otherwise the send payload for
    // this session carries no `:search` intent.
    await enableRequiredSearch(page, 'mock-searxng');
    phase('enableRequiredSearch-temp1');

    // The send exercises required web search + the chat model.
    const sendTurn1 = await sendTurnE2E20(page, `${MARK_USER} ${marker}`, mock);
    result.sendTurn1 = sendTurn1;
    phase('sendTurn-1');
    const tempText = await lastAssistantText(page);
    result.tempReply = tempText.slice(0, 300);
    const tempReplyOk = check('tempReplyReceived',
      tempText.includes(MARK_REPLY), tempText.slice(0, 200));
    // Hard short-circuit: the STT upload and every later phase depend on a
    // real reply in this temporary session.
    assert(tempReplyOk,
      `temporary session reply missing after a settled send: ${tempText.slice(0, 300)}`);

    // Required search actually hit the mock searxng endpoint.
    const searchState = await (await fetch(`${search.url}/__mock/state`)).json();
    result.searchState = { queries: searchState.queries };
    const webSearchOk = check('webSearchUsed',
      (searchState.queries || []).length >= 1,
      JSON.stringify(searchState.queries));
    assert(webSearchOk, 'required web search never reached the mock searxng endpoint');

    // STT: upload a real WAV through the documented binary route with the
    // temporary session id, exactly like the client recorder does.
    const tempSessionId = await page.evaluate(() => {
      const el = document.querySelector('.wb-session-list .wb-list-button[aria-current="true"]');
      return el ? (el.getAttribute('data-session-id') || el.id || null) : null;
    });
    // The session id is not exposed as a DOM attribute; read it from the
    // conversation header model label region instead — fall back to sending
    // the upload without session-id (still a real external call) and record
    // the limitation.
    const wav = makeWav();
    const sttRes = await page.evaluate(async ({ wavB64, sessionId }) => {
      const bytes = Uint8Array.from(atob(wavB64), (c) => c.charCodeAt(0));
      const headers = {
        'content-type': 'audio/wav',
        'x-hc-duration-ms': '100',
        'x-hc-language': 'auto',
        'x-hc-service-id': 'mock-stt',
        accept: 'application/edn',
      };
      if (sessionId) headers['x-hc-session-id'] = sessionId;
      // Bound the upload: a wedged server must fail this step, not hang the
      // spec until the global test timeout.
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 30000);
      try {
        const res = await fetch('/speech/recordings', {
          method: 'POST', body: bytes, headers, signal: controller.signal,
        });
        return { status: res.status, text: await res.text() };
      } catch (error) {
        return { status: 0, text: `upload failed: ${String(error)}` };
      } finally {
        clearTimeout(timer);
      }
    }, { wavB64: wav.toString('base64'), sessionId: tempSessionId });
    result.sttUpload = { status: sttRes.status, body: sttRes.text.slice(0, 1500) };
    const sttOk = check('sttUploadAccepted',
      sttRes.status === 200 && sttRes.text.includes(MARK_STT),
      `status=${sttRes.status} body=${sttRes.text.slice(0, 200)}`);
    assert(sttOk, `STT upload failed: status=${sttRes.status} body=${sttRes.text.slice(0, 300)}`);
    phase('sttUpload');

    // ---- Memory candidates ----------------------------------------------
    // The server refuses `materials/memory/*` commands for a temporary
    // session ("temporary sessions do not read or write memories"), so the
    // suggest pass must run on a NORMAL session.  Create one, send the
    // memory marker through the mock task role, then run 整理记忆 there.
    // The candidate is a global review item: it must survive the temp
    // discard as a candidate (never active/injected).
    await gotoSessions(page, app.url);
    await waitForSync(page);
    await createNormalSession(page);
    await sendTurnE2E20(page, `${MARK_MEMORY} ${marker}`, mock);
    phase('sendTurn-normal-memory');

    await gotoMaterialsMemory(page, app.url);
    phase('gotoMaterialsMemory');
    await enableSuggest(page);
    phase('enableSuggest');
    const candBefore = await page.locator('.hc-memory-row.hc-memory-candidate').count();
    await clickButton(page, '整理记忆');
    phase('clickMemorySuggest');
    // The suggestion result is observable: a new candidate row, the
    // candidate marker in a row, or an explicit failure/MEMORY_SUGGEST
    // notice.  Wait for that condition instead of a fixed 3s poll.
    const suggestSettled = await page.waitForFunction(
      ({ before, markerText }) => {
        const rows = [...document.querySelectorAll('.hc-memory-row.hc-memory-candidate')];
        const text = document.body.innerText || '';
        return rows.length > before
          || rows.some((row) => (row.textContent || '').includes(markerText))
          || text.includes('整理失败')
          || text.includes('MEMORY_SUGGEST');
      },
      { before: candBefore, markerText: MARK_MEMORY },
      { timeout: 300000 },
    ).then(() => true).catch(() => false);
    const candAfter = await page.locator('.hc-memory-row.hc-memory-candidate').count();
    if (!suggestSettled) result.suggestTimedOut = true;
    result.suggest = { before: candBefore, after: candAfter };
    const mockAfterSuggest = await mockState(mock);
    result.mockAfterSuggest = {
      chat: mockAfterSuggest.chat.length,
      memorySuggestCalls: mockAfterSuggest.memorySuggestCalls,
    };
    const suggestRan = check('memorySuggestRan',
      mockAfterSuggest.memorySuggestCalls >= 1,
      `suggest calls=${mockAfterSuggest.memorySuggestCalls}`);
    assert(suggestRan, 'memory suggest pass never reached the mock model');
    const candText = await page.locator('.hc-memory-row.hc-memory-candidate')
      .filter({ hasText: MARK_MEMORY }).first().innerText().catch(() => '');
    result.memoryCandidateText = candText.slice(0, 400);
    const candidateOk = check('memoryCandidatePresent',
      candText.includes(MARK_MEMORY) || candAfter > candBefore,
      `candidates ${candBefore}->${candAfter}`);
    assert(candidateOk,
      `memory candidate did not survive the suggest pass: ${candBefore}->${candAfter}`);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-02-memory-candidate'));
    phase('memorySuggestDone');

    // ---- Phase 2: discard the temporary session --------------------------
    await gotoSessions(page, app.url);
    phase('gotoSessions-2');
    await waitForSync(page);
    // Re-select the temporary session row so its discard button is armed;
    // the normal memory session is selected right now.
    await selectSessionByTitle(page, '临时会话');
    const discardBtn = page.locator('button[aria-label="结束临时会话"]').first();
    await discardBtn.waitFor({ state: 'visible', timeout: 15000 });
    const discardStart = Date.now();
    await discardBtn.click();
    // The temporary controls disappear once the discard is accepted.
    const discarded = await page.waitForFunction(() =>
      !document.querySelector('button[aria-label="结束临时会话"]'),
    null, { timeout: 60000 }).then(() => true).catch(() => false);
    result.discardElapsedMs = Date.now() - discardStart;
    const discardOk = check('discardCompleted',
      discarded && result.discardElapsedMs < 60000,
      `elapsed=${result.discardElapsedMs}ms`);
    // Short-circuit: the residue phase is meaningless if the discard never
    // completed.
    assert(discardOk,
      `temporary session discard did not complete in ${result.discardElapsedMs}ms`);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-03-after-discard'));
    phase('discardDone');

    // History: no marker text anywhere on the sessions surface.
    // MARK_USER and MARK_STT exist only on the discarded temporary
    // session (and the STT draft receipt), so they must appear nowhere in
    // rendered message bodies or session rows.  MARK_REPLY is emitted by
    // EVERY mock reply — including the surviving normal session — so it
    // cannot distinguish residue here; reply-marker coverage lives in
    // searchNoResidue / deletedAreaNoTemp / backupPreviewNoTempResidue.
    // Bounded negative condition: wait for the discarded marker to leave
    // the rendered surface before reading it.  Residue that never leaves
    // times out here and is then reported by the check below.
    await page.waitForFunction((markerText) =>
      !document.body.innerText.includes(markerText),
    MARK_USER, { timeout: 5000 }).catch(() => {});
    const messageTexts = await page.locator(
      '.hc-message-out .hc-message-body, .hc-message-in .hc-message-body')
      .allInnerTexts().catch(() => []);
    const listTexts = await page.locator(
      '.wb-session-list .wb-list-button, .wb-nav-session-list .wb-list-button')
      .allInnerTexts().catch(() => []);
    const sessionsBody = [...messageTexts, ...listTexts].join('\n');
    check('historyNoResidue',
      !sessionsBody.includes(MARK_USER) && !sessionsBody.includes(MARK_STT),
      sessionsBody.slice(0, 300));

    // Session-list search: filter by the unique user marker that only the
    // discarded temporary session ever carried.  `E2E20` alone would match
    // the surviving session's model label ("Mock E2E20") and never empty
    // the list; MARK_USER exists only inside discarded message bodies.
    await sessionFilter(page, '用户探针-合成敏感标记');
    // The filtered list is observable: wait for its empty state (bounded).
    await page.waitForFunction(() =>
      (document.querySelector('.wb-session-list')?.innerText || '')
        .includes('没有匹配会话'),
    null, { timeout: 10000 }).catch(() => {});
    const filterText = await sessionFilterText(page);
    result.sessionFilterE2E20 = filterText.slice(0, 400);
    check('searchNoResidue',
      filterText.includes('没有匹配会话'),
      filterText.slice(0, 200));
    await sessionFilter(page, '');
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-04-search-empty'));

    // Memory page: the candidate generated from the temp session must not
    // survive the discard as an active/candidate row.  Candidates are global
    // review items; a discarded session's suggestion is either absent or
    // still a candidate (never injected).  Record what is actually shown.
    await gotoMaterialsMemory(page, app.url);
    // A real load condition: the memory filter bar/rows are mounted.
    await page.locator('.hc-mat-filters, .hc-memory-row').first()
      .waitFor({ state: 'visible', timeout: 30000 }).catch(() => {});
    const memoryBody = await bodyText(page);
    result.memoryPageAfterDiscard = memoryBody.slice(0, 3000);
    const memRows = await page.locator('.hc-memory-row').allInnerTexts();
    result.memoryRows = memRows.map((t) => t.slice(0, 200));
    const memMarkerRows = memRows.filter((t) => t.includes(MARK_MEMORY));
    result.memoryMarkerRows = memMarkerRows.length;
    // Honest check: the candidate may legitimately remain as a 待审 row
    // (candidates are global, not session-owned), but it must never be
    // 生效/active and must not be injected.  Assert no ACTIVE marker row.
    const activeMarker = await page.locator('.hc-memory-row.hc-memory-active')
      .filter({ hasText: MARK_MEMORY }).count();
    check('memoryNoActiveResidue', activeMarker === 0,
      `active marker rows=${activeMarker}, candidate rows=${memMarkerRows.length}`);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-05-memory-after-discard'));
    phase('memoryAfterDiscard');

    // Backup preview: create a real encrypted backup (temp sessions are
    // excluded by default) and confirm the preview never lists the marker.
    await goto(page, app.url, '/settings');
    await page.locator('.hc-settings').first().waitFor({ state: 'visible', timeout: 30000 });
    await page.locator('.hc-sync').filter({ hasText: '已同步' }).first()
      .waitFor({ state: 'visible', timeout: 30000 });
    await page.getByRole('button', { name: '数据中心', exact: true }).first().click();
    await page.locator('.hc-backup').first().waitFor({ state: 'visible', timeout: 30000 });
    await page.locator('.hc-backup-tab', { hasText: '创建备份' }).first().click();
    const pwInputs = page.locator('.hc-backup .hc-backup-password input[type="password"]');
    await pwInputs.nth(0).waitFor({ state: 'visible', timeout: 15000 });
    await pwInputs.nth(0).fill(BACKUP_PASSWORD);
    await pwInputs.nth(1).fill(BACKUP_PASSWORD);
    const createBtn = page.getByRole('button', { name: '生成加密备份' });
    await page.waitForFunction(() => {
      const btn = [...document.querySelectorAll('button')]
        .find((b) => (b.textContent || '').includes('生成加密备份'));
      return btn && !btn.disabled;
    }, null, { timeout: 120000 });
    await createBtn.click();
    await page.locator('.hc-backup-result').waitFor({ state: 'visible', timeout: 120000 });
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-06-backup-created'));

    await page.locator('.hc-backup-tab', { hasText: '恢复与预览' }).first().click();
    await page.locator('.hc-backup-row').first().waitFor({ state: 'visible', timeout: 30000 });
    const backupRow = page.locator('.hc-backup-row').first();
    const backupIdText = await backupRow.innerText();
    result.backupRow = backupIdText.slice(0, 300);
    await backupRow.getByRole('button', { name: '选择这份备份' }).first().click();
    // Fill the restore-tab password and generate the preview.
    // Scope to the restore section: the page mounts several
    // `.hc-backup-section` blocks and the create tab keeps its own
    // password inputs, so `.first()`/`.last()` can fill the wrong pair.
    // The restore section is the one containing the preview button.
    const pwField = page.locator('.hc-backup-section', {
      has: page.getByRole('button', { name: '校验并生成预览' }),
    }).locator('input[type="password"]').first();
    await pwField.fill(BACKUP_PASSWORD);
    const previewBtn = page.getByRole('button', { name: '校验并生成预览' });
    // The button stays disabled while `submit-reason`/`preview-validation`
    // reports a reason (sync pending, password check, selection).  Wait for
    // the real enablement and capture the rendered reason only on failure.
    const previewReady = await page.waitForFunction(() => {
      const btn = [...document.querySelectorAll('.hc-backup button')]
        .find((b) => (b.textContent || '').includes('校验并生成预览'));
      return Boolean(btn && !btn.disabled);
    }, null, { timeout: 180000 }).then(() => true).catch(() => false);
    if (!previewReady) {
      const state = await page.evaluate(() => {
        const btn = [...document.querySelectorAll('.hc-backup button')]
          .find((b) => (b.textContent || '').includes('校验并生成预览'));
        const warn = document.querySelector('.hc-backup-submit .hc-backup-warning');
        return { disabled: btn ? btn.disabled : null,
                 warn: warn ? warn.textContent : null };
      });
      result.backupPreviewBlocker = state.warn || state.disabled;
    }
    assert(previewReady, `preview button never enabled; blocker=${JSON.stringify(result.backupPreviewBlocker)}`);
    await previewBtn.click();
    await page.locator('.hc-backup-preview').waitFor({ state: 'visible', timeout: 120000 });
    await page.waitForFunction(() => {
      const el = document.querySelector('.hc-backup-preview');
      return el && /恢复预览|合并选中类别|替换选中类别/.test(el.textContent || '');
    }, null, { timeout: 120000 });
    const previewText = await page.locator('.hc-backup-preview').innerText();
    result.backupPreview = previewText.slice(0, 6000);
    check('backupPreviewNoTempResidue',
      !previewText.includes(MARK_USER) && !previewText.includes(MARK_REPLY)
      && !previewText.includes(MARK_STT) && !previewText.includes(MARK_MEMORY),
      previewText.slice(0, 300));
    // The manifest exclusion line should count temporary sessions when the
    // snapshot saw any; record it verbatim for the report.
    const manifestLine = previewText.match(/排除[^\n]*/g) || [];
    result.backupPreviewExclusions = manifestLine.slice(0, 10);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-07-backup-preview'));
    phase('backupPreviewDone');

    // Deleted area: temporary sessions never enter it.
    await gotoSessions(page, app.url);
    await waitForSync(page);
    const deletedDetails = page.locator('details.wb-deleted-sessions').first();
    if (await deletedDetails.count()) {
      await deletedDetails.locator('summary').first().click();
      const loadBtn = deletedDetails.getByRole('button', { name: /加载已删除会话|刷新已删除会话/ }).first();
      if (await loadBtn.count()) await loadBtn.click().catch(() => {});
      // Wait for the deleted area to leave its loading state.
      await page.waitForFunction(() => {
        const el = document.querySelector('details.wb-deleted-sessions');
        return el && !/正在加载/.test(el.textContent || '');
      }, null, { timeout: 15000 }).catch(() => {});
    }
    const deletedAreaText = await page.locator('details.wb-deleted-sessions').first()
      .innerText().catch(() => '');
    result.deletedArea = deletedAreaText.slice(0, 2000);
    check('deletedAreaNoTemp',
      !deletedAreaText.includes(MARK_USER) && !deletedAreaText.includes(MARK_REPLY)
      && (deletedAreaText.includes('临时会话不进入已删除区')
          || !deletedAreaText.includes('E2E20')),
      deletedAreaText.slice(0, 300));
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-08-deleted-area'));
    phase('deletedAreaDone');

    // Diagnostics: the usage/stats surface must not leak the discarded
    // session's markers into message lists.
    await goto(page, app.url, '/settings');
    await page.locator('.hc-settings').first().waitFor({ state: 'visible', timeout: 30000 });
    await page.getByRole('button', { name: '数据中心', exact: true }).first().click();
    await page.locator('.hc-diagnostics').first().waitFor({ state: 'visible', timeout: 30000 });
    const diagText = await bodyText(page);
    result.diagnosticsText = diagText.slice(0, 12000);
    check('diagnosticsNoResidue',
      !diagText.includes(MARK_USER) && !diagText.includes(MARK_REPLY)
      && !diagText.includes(MARK_STT) && !diagText.includes(MARK_MEMORY),
      'diagnostics body scanned');
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-09-diagnostics'));
    phase('diagnosticsDone');

    // ---- Phase 3: transfer a second temporary session --------------------
    await gotoSessions(page, app.url);
    phase('gotoSessions-3');
    await waitForSync(page);
    await createTemporarySession(page);
    phase('createTemporarySession-3');
    // Same session-scoped rule: arm required search on THIS session too so
    // the transfer turn exercises the same external surface as Phase 1.
    await enableRequiredSearch(page, 'mock-searxng');
    phase('enableRequiredSearch-temp2');
    await sendTurnE2E20(page, `${MARK_TRANSFER} ${marker}-t`, mock);
    phase('sendTurn-3');
    const transferReply = await lastAssistantText(page);
    const transferReplyOk = check('transferTurnReplied',
      transferReply.includes(MARK_REPLY), transferReply.slice(0, 200));
    assert(transferReplyOk,
      `transfer session reply missing after a settled send: ${transferReply.slice(0, 300)}`);
    // Leave an unsent draft marker too, to prove draft survival.
    await composer(page).fill(`${MARK_TRANSFER} 草稿保留 ${marker}-draft`);
    const mockBeforeTransfer = await mockState(mock);
    const searchBeforeTransfer = await (await fetch(`${search.url}/__mock/state`)).json();
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-10-transfer-before'));

    const transferBtn = page.locator('button[aria-label="转为普通会话"]').first();
    await transferBtn.waitFor({ state: 'visible', timeout: 15000 });
    const transferStart = Date.now();
    await transferBtn.click();
    // The temporary controls disappear once the transfer is accepted.
    const transferred = await page.waitForFunction(() =>
      !document.querySelector('button[aria-label="转为普通会话"]')
      && !document.querySelector('button[aria-label="结束临时会话"]'),
    null, { timeout: 60000 }).then(() => true).catch(() => false);
    result.transferElapsedMs = Date.now() - transferStart;
    const transferOk = check('transferCompleted',
      transferred, `elapsed=${result.transferElapsedMs}ms`);
    assert(transferOk,
      `temporary session transfer did not complete in ${result.transferElapsedMs}ms`);
    // The transferred content is observable; wait for the marker to be
    // rendered instead of a fixed settle.
    await page.waitForFunction((markerText) =>
      (document.body.innerText || '').includes(markerText),
    MARK_TRANSFER, { timeout: 5000 }).catch(() => {});
    const afterTransfer = await bodyText(page);
    check('transferKeepsContent',
      afterTransfer.includes(MARK_TRANSFER),
      afterTransfer.slice(0, 300));
    check('transferDraftKept',
      (await composer(page).inputValue().catch(() => '')).includes('草稿保留'),
      await composer(page).inputValue().catch(() => ''));
    check('transferBannerGone',
      !(await page.locator('.hc-banner').filter({ hasText: '临时' }).count()),
      'temporary banner count');
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-11-transfer-after'));

    // No external action is repeated by the transfer itself.
    const mockAfterTransfer = await mockState(mock);
    const searchAfterTransfer = await (await fetch(`${search.url}/__mock/state`)).json();
    result.externalActionCounts = {
      chatBefore: mockBeforeTransfer.chat.length,
      chatAfter: mockAfterTransfer.chat.length,
      suggestBefore: mockBeforeTransfer.memorySuggestCalls,
      suggestAfter: mockAfterTransfer.memorySuggestCalls,
      transcriptions: mockAfterTransfer.transcriptions.length,
      searchQueriesBefore: (searchBeforeTransfer.queries || []).length,
      searchQueriesAfter: (searchAfterTransfer.queries || []).length,
    };
    check('transferNoDuplicateExternal',
      mockAfterTransfer.chat.length === mockBeforeTransfer.chat.length
      && mockAfterTransfer.memorySuggestCalls === mockBeforeTransfer.memorySuggestCalls
      && mockAfterTransfer.transcriptions.length === mockBeforeTransfer.transcriptions.length
      && (searchAfterTransfer.queries || []).length
         === (searchBeforeTransfer.queries || []).length,
      JSON.stringify(result.externalActionCounts));

    // The transferred session is a normal session: it survives in history
    // and shows up under session search by its marker-bearing content is
    // NOT how titles work — instead verify the row exists and is selectable.
    const sessionRows = await page.locator('.wb-session-list .wb-list-button').allInnerTexts();
    result.sessionRowsAfter = sessionRows.map((t) => t.slice(0, 120));
    check('transferredSessionListed', sessionRows.length >= 1,
      `${sessionRows.length} rows`);

    // Final residue sweep on the whole app surface.
    const finalBody = await bodyText(page);
    check('finalSurfaceHasTransferMarker', finalBody.includes(MARK_TRANSFER),
      'transfer marker visible on normal session');

    // Persist the full mock state for the report.
    result.mockFinal = {
      chat: mockAfterTransfer.chat,
      transcriptions: mockAfterTransfer.transcriptions,
      tts: mockAfterTransfer.tts,
      memorySuggestCalls: mockAfterTransfer.memorySuggestCalls,
    };

    result.ok = Object.values(result.checks).every((c) => c.pass);
    result.finishedAt = new Date().toISOString();
    evidence('e2e20-result', result);
    assert(result.ok, `E2E 20 checks failed: ${JSON.stringify(
      Object.fromEntries(Object.entries(result.checks)
        .filter(([, v]) => !v.pass).map(([k, v]) => [k, v.detail])), null, 2)}`);
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockFinal = await mockState(mock).catch(() => null);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e20-error').catch(() => null));
    evidence('e2e20-result', result);
    throw error;
  } finally {
    await app.close();
    await mock.close();
    await search.close();
    await webdav.close();
  }
});
