// E2E 12: worldbook + instruction cards, driven end to end through the real
// app.  Books, entries, cards and selections are created through the
// loopback control channel (`:command` op), the same trusted path external
// drivers use; sends go through `:send-message` on the same channel.  The
// mock model records every request body, so each assertion reads the actual
// system-message injection, while the conversation context panel is checked
// in the browser for the "命中但未提供" budget row and card scopes.
//
// Covered:
//   - keyword :any / :all matching inside the 4-user-text scan window
//   - sticky (持续) and cooldown (冷却) counters advance per accepted user
//     turn; nudge continuations never consume an extra turn
//   - a hit entry over the 4000-char worldbook budget reports
//     "命中但未提供 … 超出本轮预算" in the frozen panel
//   - entry move to another book drops it from injection; moving back
//     restores it (the undo round-trip is the same command with the
//     recorded previous book ids)
//   - per-session counters and per-turn cards are isolated across sessions
//   - instruction cards: turn-scoped card injects once, session-scoped card
//     persists until cleared; clearing removes it from the next send
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { test } from '@playwright/test';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert, clientBundleInfo, composer, goto, shot, waitForShell, waitForSync,
  writeJson,
} from './support/ui.mjs';
import { sourceTreeSha256 } from './support/evidence.mjs';

const EVIDENCE = path.join(REPO_ROOT, 'plans/evidence/e2e-12');

// ---------------------------------------------------------------------------
// Loopback control channel (hc.server.app.singleton EDN line protocol).
// The endpoint state lives in <java.io.tmpdir>/herdchestra-<user>/instance.edn.
// startApp pins java.io.tmpdir to <tempRoot>/tmp, so the endpoint is private
// to this app instance — no other Herdchestra can hold it.
// ---------------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function safeUser() {
  return (process.env.USER || os.userInfo().username || 'user')
    .replace(/[^A-Za-z0-9._-]/g, '_');
}

async function controlEndpoint(app) {
  const file = path.join(app.tempRoot, 'tmp', `herdchestra-${safeUser()}`,
    'instance.edn');
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    if (fs.existsSync(file)) {
      const text = fs.readFileSync(file, 'utf8');
      const port = Number((text.match(/:port (\d+)/) || [])[1]);
      const token = (text.match(/:token "([^"]+)"/) || [])[1];
      if (port && token) return { port, token };
    }
    await sleep(200);
  }
  throw new Error(`control endpoint not published at ${file}`);
}

function ednString(value) {
  return JSON.stringify(String(value));
}

function ednValue(value) {
  if (value === null || value === undefined) return 'nil';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') return String(value);
  if (typeof value === 'string') {
    if (value.startsWith(':')) return value;
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(ednValue).join(' ')}]`;
  if (value instanceof Set) return `#{${[...value].map(ednValue).join(' ')}}`;
  if (value && typeof value === 'object') {
    return `{${Object.entries(value)
      .map(([k, v]) => `${k.startsWith(':') ? k : `:${k}`} ${ednValue(v)}`)
      .join(' ')}}`;
  }
  throw new Error(`ednValue: unsupported ${typeof value}`);
}

function controlRequest(endpoint, request) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(endpoint.port, '127.0.0.1', () => {
      socket.write(`${ednValue({ ...request, token: endpoint.token })}\n`);
    });
    let buffer = '';
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error(
        `control request timed out: ${JSON.stringify(request).slice(0, 200)}`));
    }, 60000);
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      const newline = buffer.indexOf('\n');
      if (newline >= 0) {
        clearTimeout(timer);
        socket.destroy();
        resolve(buffer.slice(0, newline));
      }
    });
    socket.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

// Minimal EDN reader for the reply maps we consume: maps, vectors, sets,
// strings, keywords, numbers, booleans, nil.
function parseEdn(text) {
  let i = 0;
  const skip = () => { while (/[\s,]/.test(text[i] || '')) i += 1; };
  const parse = () => {
    skip();
    const c = text[i];
    if (c === '{') {
      i += 1;
      const out = {};
      for (;;) {
        skip();
        if (text[i] === '}') { i += 1; return out; }
        const key = parse();
        const value = parse();
        out[key] = value;
      }
    }
    if (c === '[') {
      i += 1;
      const out = [];
      for (;;) {
        skip();
        if (text[i] === ']') { i += 1; return out; }
        out.push(parse());
        skip();
        if (text[i] === ',') i += 1;
      }
    }
    if (c === '#' && text[i + 1] === '{') {
      i += 2;
      const out = [];
      for (;;) {
        skip();
        if (text[i] === '}') { i += 1; return out; }
        out.push(parse());
        skip();
        if (text[i] === ',') i += 1;
      }
    }
    if (c === '"') {
      i += 1;
      let out = '';
      while (i < text.length && text[i] !== '"') {
        if (text[i] === '\\') {
          i += 1;
          const esc = text[i];
          out += esc === 'n' ? '\n' : esc === 't' ? '\t' : esc === 'r' ? '\r' : esc;
          i += 1;
        } else {
          out += text[i];
          i += 1;
        }
      }
      i += 1;
      return out;
    }
    if (c === ':') {
      i += 1;
      let out = '';
      while (i < text.length && /[A-Za-z0-9_.*+!?<>=/-]/.test(text[i])) {
        out += text[i];
        i += 1;
      }
      return `:${out}`;
    }
    const token = /^[^\s}\],]+/.exec(text.slice(i));
    if (!token) throw new Error(`edn parse stuck at ${i}: ${text.slice(i, i + 40)}`);
    i += token[0].length;
    const raw = token[0];
    if (raw === 'nil') return null;
    if (raw === 'true') return true;
    if (raw === 'false') return false;
    if (/^-?\d+(\.\d+)?$/.test(raw)) return Number(raw);
    return raw;
  };
  const value = parse();
  return value;
}

async function control(endpoint, request) {
  const raw = await controlRequest(endpoint, request);
  try {
    return parseEdn(raw);
  } catch (error) {
    return { parseError: String(error), raw: raw.slice(0, 2000) };
  }
}

function command(endpoint, sessionId, kind, fields = {}) {
  return control(endpoint, {
    op: ':command',
    payload: {
      ':kind': kind,
      ...(sessionId ? { ':session-id': sessionId } : {}),
      ...fields,
    },
  });
}

function replyOk(reply) {
  return reply && reply[':ok?'] === true;
}

function replyError(reply) {
  if (!reply) return 'no reply';
  if (reply.parseError) return `parse ${reply.parseError}: ${reply.raw}`;
  const error = reply[':error'];
  return error ? JSON.stringify(error).slice(0, 500)
    : JSON.stringify(reply).slice(0, 500);
}

async function mustCommand(endpoint, sessionId, kind, fields, label) {
  const reply = await command(endpoint, sessionId, kind, fields);
  assert(replyOk(reply), `${label} refused: ${replyError(reply)}`);
  return reply;
}

// The server projects materials/component snapshots for its presentation
// active session (`:session/active-id`), which `session/create` sets to the
// newest session.  `:focus-or-create` re-points it so the pushed baseline
// carries the selected session's worldbook runtime; without it the client
// rejects the foreign-session snapshot as cross-session and the page stays
// empty.
async function focusSession(endpoint, sessionId) {
  const reply = await control(endpoint, {
    op: ':focus-or-create',
    'session-id': sessionId,
  });
  assert(reply && reply[':ok?'] === true,
    `focus-or-create ${sessionId} refused: ${replyError(reply)}`);
  return reply;
}

function receiptResult(reply) {
  const receipt = reply?.[':receipt'];
  return receipt?.[':result'] ?? receipt?.result ?? {};
}

async function createSession(endpoint, title) {
  const reply = await mustCommand(endpoint, null, ':session/create',
    { ':title': title }, `session/create ${title}`);
  const id = reply?.[':receipt']?.[':session-id']
    || reply?.[':receipt']?.[':session']?.[':session-id'];
  assert(typeof id === 'string' && id.length > 0,
    `session id missing in ${JSON.stringify(reply).slice(0, 500)}`);
  return id;
}

async function sendUserTurn(endpoint, sessionId, text, extra = {}) {
  return mustCommand(endpoint, sessionId, ':send-message', {
    ':text': text,
    ...extra,
  }, `send-message ${text.slice(0, 40)}`);
}

async function mockState(mock) {
  return (await fetch(`${mock.url}/__mock/state`)).json();
}

// The request bodies ring-buffer keeps the last 12 raw payloads.  A user
// turn's request prepends {"role":"system","content":"…"} carrying
// "Worldbook:\n…" and "Instructions:\n…"; nudge continuations share the last
// user text but carry no injection, so the body is selected by marker AND
// by the presence of a Worldbook/Instructions system message.
function userTurnBodies(state) {
  return (state.bodies || [])
    .map((raw) => {
      try { return JSON.parse(raw); } catch { return null; }
    })
    .filter(Boolean);
}

function systemText(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  return messages
    .filter((m) => m && (m.role === 'system' || m.role === 'developer'))
    .map((m) => (typeof m.content === 'string' ? m.content : JSON.stringify(m.content)))
    .join('\n');
}

function lastUserText(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const last = [...messages].reverse().find((m) => m && m.role === 'user');
  if (!last) return '';
  if (typeof last.content === 'string') return last.content;
  if (Array.isArray(last.content)) {
    return last.content
      .filter((p) => p && p.type === 'text')
      .map((p) => p.text)
      .join('');
  }
  return '';
}

function hasInjection(body) {
  const sys = systemText(body);
  return sys.includes('Worldbook:') || sys.includes('Instructions:')
    || sys.includes('Memory:');
}

// Wait until the mock has captured the user-turn request for MARKER.
// Returns the system text of that request.
async function waitTurnSystem(mock, marker, timeout = 180000) {
  const deadline = Date.now() + timeout;
  let last = null;
  while (Date.now() < deadline) {
    const state = await mockState(mock).catch(() => null);
    if (state) {
      const bodies = userTurnBodies(state);
      for (let i = bodies.length - 1; i >= 0; i -= 1) {
        const body = bodies[i];
        if (lastUserText(body).includes(marker) && hasInjection(body)) {
          return systemText(body);
        }
      }
      last = state;
    }
    await sleep(500);
  }
  throw new Error(`user-turn request for ${marker} never arrived; ` +
    `last mock state: ${JSON.stringify(last).slice(0, 800)}`);
}

// ---------------------------------------------------------------------------
// Browser helpers for the frozen context panel and the materials page.
// ---------------------------------------------------------------------------

async function openPanel(page) {
  const details = page.locator('details.hc-round-context');
  const count = await details.count();
  if (!count) return '';
  let fallback = '';
  for (let i = count - 1; i >= 0; i -= 1) {
    const text = (await details.nth(i).evaluate((el) => el.textContent || '')).trim();
    if (text.includes('本轮上下文 · 上次发送')) return text;
    if (!fallback) fallback = text;
  }
  return fallback;
}

async function openSession(page, sessionId) {
  await page.evaluate((id) => { window.location.hash = `#/sessions/${id}`; }, sessionId);
  await page.waitForTimeout(1500);
  await composer(page).waitFor({ state: 'visible', timeout: 20000 });
}

async function hashTo(page, hash) {
  await page.evaluate((h) => { window.location.hash = h; }, hash);
  await page.waitForTimeout(1200);
}

async function openMaterials(page) {
  if (!page.url().includes('#/materials')) await hashTo(page, '#/materials');
  await page.waitForTimeout(800);
}

// Poll the materials worldbook tab until BOOK-NAME renders.  Direct
// navigation keeps the live session selection and realtime channel;
// callers fall back to reload+reselect when the snapshot does not arrive.
async function openWorldbookReady(page, bookName, budgetMs = 45000) {
  const deadline = Date.now() + budgetMs;
  while (Date.now() < deadline) {
    await openMaterials(page);
    const wbTab = page.getByRole('tab', { name: '世界书', exact: true }).first();
    if (await wbTab.count()) await wbTab.click().catch(() => {});
    await page.waitForTimeout(800);
    if ((await bodyText(page)).includes(bookName)) return true;
    await sleep(1000);
  }
  return false;
}

async function bodyText(page) {
  return (await page.locator('body').innerText()).trim();
}

async function waitFrozenPanel(page, timeout = 90000) {
  await page.waitForFunction(() => {
    const panels = [...document.querySelectorAll('details.hc-round-context')];
    return panels.some((el) => (el.textContent || '').includes('本轮上下文 · 上次发送'));
  }, null, { timeout });
  // Give the projection one extra beat so the newest run's snapshot wins.
  await sleep(1200);
}

// ---------------------------------------------------------------------------
// Test
// ---------------------------------------------------------------------------

test.describe.configure({ mode: 'serial' });

test('@core E2E 12 worldbook + instruction cards', async ({ page, browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE, { recursive: true });
  const stamp = Date.now().toString(36);
  const marker = `E2E12-${stamp}`;

  const mock = await startMockModel({
    captureBodies: true,
    reply: `E2E12 回复 ${marker}`,
    chunkSize: 400,
    chunkDelayMs: 5,
  });
  const app = await startApp({
    evidenceDir: EVIDENCE,
    keepTemp: true,
    nudgeRoundBudget: 1,
    models: {
      connections: [{
        id: 'mock',
        name: 'E2E12 Mock',
        baseUrl: `${mock.url}/v1`,
        models: [{
          id: 'mock-fast',
          displayName: 'Mock Fast',
          maxOutputTokens: 2048,
          capabilities: ['tools'],
        }],
      }],
      roles: { main: { connection: 'mock', model: 'mock-fast' } },
    },
  });

  const endpoint = await controlEndpoint(app);
  const result = {
    test: 'E2E 12',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    tempRoot: app.tempRoot,
    mockUrl: mock.url,
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    bundleSha256Expected: '45f11529a22da07d23e665c412ad6b1611758d63e28bd464727efa32e174fd30',
    treeSha256Expected: '76cf2fd01952d06d46932eea1406f849452ed6e0285b2458f7794d1214a42f7b',
    sourceTreeSha256: sourceTreeSha256(),
    freeze: { bundle: '45f11529', tree: '76cf2fd0' },
    checks: {},
    screenshots: [],
    details: {},
  };
  const evidence = (name, value) => writeJson(EVIDENCE, name, value);
  const ck = (name, pass, detail) => {
    result.checks[name] = { pass: Boolean(pass), detail };
    return Boolean(pass);
  };

  const bookAName = `E2E12书甲-${stamp}`;
  const bookBName = `E2E12书乙-${stamp}`;
  const bookCName = `E2E12书丙-${stamp}`;
  let bookA;
  let bookB;
  let bookC;
  let entryAny;
  let entryAll;
  let entrySticky;
  let entryCool;
  let entryBudget;
  let entryConst;
  let cardTurn;
  let cardSession;
  let sessionA;
  let sessionB;

  const createBook = async (sessionId, name) => {
    const reply = await mustCommand(endpoint, sessionId, ':materials/worldbook/create',
      { ':name': name, ':enabled?': true }, `create book ${name}`);
    const book = receiptResult(reply)[':book'] || {};
    const id = book[':id'] || book.id;
    assert(id, `book id missing: ${replyError(reply)}`);
    return id;
  };

  const bindBook = (bookId) => mustCommand(endpoint, sessionA,
    ':materials/worldbook/bind',
    { ':book-id': bookId, ':assistant-id': 'default' }, `bind ${bookId}`);

  const createEntry = async (fields) => {
    const reply = await mustCommand(endpoint, sessionA,
      ':materials/worldbook/entry/create', { ...fields },
      `create entry ${fields[':title']}`);
    const entry = receiptResult(reply)[':entry'] || {};
    const id = entry[':id'] || entry.id;
    assert(id, `entry id missing: ${replyError(reply)}`);
    return id;
  };

  const keywordTrigger = (keywords, match) => ({
    ':trigger': {
      ':kind': ':keyword',
      ':keywords': keywords.split('\n'),
      ':match': match,
    },
  });

  try {
    // Sessions come first: every :materials/* command is session-scoped and
    // needs an explicit :session-id even for library-wide writes.
    sessionA = await createSession(endpoint, `E2E12会话甲-${stamp}`);
    sessionB = await createSession(endpoint, `E2E12会话乙-${stamp}`);
    result.details.sessions = { sessionA, sessionB };
    ck('e2e12-sessions-created', Boolean(sessionA && sessionB), result.details.sessions);

    // ---- library setup -------------------------------------------------
    // Book C is created first so its constant entry sorts before every
    // keyword entry and deterministically exhausts the 4000-char budget.
    bookB = await createBook(sessionA, bookBName);   // stays unbound (move target)
    bookC = await createBook(sessionA, bookCName);   // bound; budget filler
    bookA = await createBook(sessionA, bookAName);   // bound; keyword entries
    await bindBook(bookA);
    await bindBook(bookC);
    result.details.books = { bookA, bookB, bookC };

    entryAny = await createEntry({
      ':book-id': bookA,
      ':title': 'MR-任一',
      ':body': `任一命中正文 ${marker}-any。`,
      ...keywordTrigger('星轨蓝\n恒星轨', ':any'),
    });
    entryAll = await createEntry({
      ':book-id': bookA,
      ':title': 'MR-全部',
      ':body': `全部命中正文 ${marker}-all。`,
      ...keywordTrigger('星轨蓝\n恒星轨', ':all'),
    });
    entrySticky = await createEntry({
      ':book-id': bookA,
      ':title': 'MR-持续',
      ':body': `持续正文 ${marker}-sticky。`,
      ...keywordTrigger('冷月华', ':any'),
      ':sticky': 2,
    });
    entryCool = await createEntry({
      ':book-id': bookA,
      ':title': 'MR-冷却',
      ':body': `冷却正文 ${marker}-cool。`,
      ...keywordTrigger('霜降', ':any'),
      ':cooldown': 3,
    });
    entryBudget = await createEntry({
      ':book-id': bookC,
      ':title': 'MR-预算',
      ':body': `预算正文 ${marker}-budget-` + '界'.repeat(4100),
      ':trigger': { ':kind': ':constant' },
    });
    entryConst = await createEntry({
      ':book-id': bookA,
      ':title': 'MR-始终',
      ':body': `始终提供正文 ${marker}-const。`,
      ':trigger': { ':kind': ':constant' },
    });
    result.details.entries = {
      entryAny, entryAll, entrySticky, entryCool, entryBudget, entryConst,
    };
    ck('e2e12-entries-created',
      [entryAny, entryAll, entrySticky, entryCool, entryBudget, entryConst]
        .every(Boolean), result.details.entries);

    // Instruction cards: one turn-scoped, one session-scoped.
    const cardReply1 = await mustCommand(endpoint, sessionA,
      ':materials/instruction/create',
      { ':name': `MR-仅本轮卡-${stamp}`, ':body': `仅本轮指令 ${marker}-card-turn。` },
      'create turn card');
    cardTurn = receiptResult(cardReply1)[':card']?.[':id']
      || receiptResult(cardReply1)[':card']?.id;
    const cardReply2 = await mustCommand(endpoint, sessionA,
      ':materials/instruction/create',
      { ':name': `MR-会话卡-${stamp}`, ':body': `会话指令 ${marker}-card-session。` },
      'create session card');
    cardSession = receiptResult(cardReply2)[':card']?.[':id']
      || receiptResult(cardReply2)[':card']?.id;
    assert(cardTurn && cardSession, 'instruction card ids missing');
    result.details.cards = { cardTurn, cardSession };
    ck('e2e12-cards-created', Boolean(cardTurn && cardSession), result.details.cards);

    // Session-scoped card applies to session A only.
    await mustCommand(endpoint, sessionA, ':materials/instruction/session/set',
      { ':card-ids': [cardSession] }, 'session/set cardSession');

    // ---- browser: open session A ----------------------------------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await openSession(page, sessionA);
    await focusSession(endpoint, sessionA);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e12-01-session-a'));

    // ---- turn 1: every keyword hits, counters commit --------------------
    const t1Marker = `${marker}-t1`;
    await sendUserTurn(endpoint, sessionA,
      `星轨蓝 恒星轨 冷月华 霜降 ${t1Marker} 只回复 OK`,
      { ':turn-card-ids': [cardTurn] });
    const sys1 = await waitTurnSystem(mock, t1Marker);
    result.details.turn1System = sys1;
    ck('e2e12-t1-any-hit', sys1.includes(`${marker}-any`), 'entryAny body missing');
    ck('e2e12-t1-all-hit', sys1.includes(`${marker}-all`), 'entryAll body missing');
    ck('e2e12-t1-sticky-hit', sys1.includes(`${marker}-sticky`), 'entrySticky body missing');
    ck('e2e12-t1-cool-hit', sys1.includes(`${marker}-cool`), 'entryCool body missing');
    ck('e2e12-t1-const-provided', sys1.includes(`${marker}-const`), 'entryConst body missing');
    ck('e2e12-t1-budget-hit-not-provided',
      !sys1.includes(`${marker}-budget-`),
      'budget entry body leaked into injection');
    ck('e2e12-t1-card-turn-injected', sys1.includes(`${marker}-card-turn`),
      'turn card body missing');
    ck('e2e12-t1-card-session-injected', sys1.includes(`${marker}-card-session`),
      'session card body missing');
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e12-02-turn1'));

    // Frozen panel for turn 1: worldbook section lists the hit entries as
    // provided (keyword/constant) and the over-budget constant as omitted.
    await waitFrozenPanel(page);
    let panel = await openPanel(page);
    result.details.panel1 = panel.slice(0, 6000);
    ck('e2e12-panel1-frozen', panel.includes('本轮上下文 · 上次发送'), panel.slice(0, 200));
    ck('e2e12-panel1-provided-rows',
      panel.includes('已提供') && panel.includes(entryAny)
        && panel.includes(entryConst),
      'provided rows missing');
    ck('e2e12-panel1-budget-omitted',
      panel.includes('命中但未提供') && panel.includes(entryBudget)
        && panel.includes('超出本轮预算'),
      panel.slice(0, 3000));
    ck('e2e12-panel1-card-turn', panel.includes(`MR-仅本轮卡-${stamp}`)
      && panel.includes('turn'), 'turn card row missing');
    ck('e2e12-panel1-card-session', panel.includes(`MR-会话卡-${stamp}`)
      && panel.includes('session'), 'session card row missing');
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e12-03-panel-turn1'));

    // ---- turn 2: sticky + cooldown active; turn card consumed -----------
    const t2Marker = `${marker}-t2`;
    await sendUserTurn(endpoint, sessionA, `冷月华 霜降 ${t2Marker} 只回复 OK`);
    const sys2 = await waitTurnSystem(mock, t2Marker);
    result.details.turn2System = sys2;
    ck('e2e12-t2-sticky-still-provided', sys2.includes(`${marker}-sticky`),
      'sticky entry missing on turn 2');
    ck('e2e12-t2-cool-blocked', !sys2.includes(`${marker}-cool`),
      'cooldown entry leaked on turn 2');
    ck('e2e12-t2-any-hit-window', sys2.includes(`${marker}-any`),
      'any entry lost inside the 4-text window');
    // 恒星轨 is still inside the 4-text window on turn 2, so the all-match
    // entry keeps hitting; it leaves the window from turn 3 on.
    ck('e2e12-t2-all-still-window', sys2.includes(`${marker}-all`),
      'all-match entry lost while 恒星轨 is still in window');
    ck('e2e12-t2-card-turn-cleared', !sys2.includes(`${marker}-card-turn`),
      'turn card leaked into turn 2');
    ck('e2e12-t2-card-session-persists', sys2.includes(`${marker}-card-session`),
      'session card missing on turn 2');
    ck('e2e12-t2-budget-still-omitted', !sys2.includes(`${marker}-budget-`),
      'budget entry provided on turn 2');

    await waitFrozenPanel(page);
    panel = await openPanel(page);
    result.details.panel2 = panel.slice(0, 6000);
    ck('e2e12-panel2-cooldown-omitted',
      panel.includes('命中但未提供') && panel.includes(entryCool)
        && panel.includes('冷却'),
      panel.slice(0, 3000));
    ck('e2e12-panel2-card-turn-gone',
      !panel.includes(`MR-仅本轮卡-${stamp}`),
      'turn card still listed on turn 2 panel');
    ck('e2e12-panel2-card-session-kept',
      panel.includes(`MR-会话卡-${stamp}`), 'session card row missing on turn 2');

    // ---- turn 3: cooldown still blocks; sticky last covered turn --------
    const t3Marker = `${marker}-t3`;
    await sendUserTurn(endpoint, sessionA, `霜降 ${t3Marker} 只回复 OK`);
    const sys3 = await waitTurnSystem(mock, t3Marker);
    result.details.turn3System = sys3;
    ck('e2e12-t3-cool-still-blocked', !sys3.includes(`${marker}-cool`),
      'cooldown entry leaked on turn 3');
    ck('e2e12-t3-sticky-last-covered', sys3.includes(`${marker}-sticky`),
      'sticky entry missing on its last sticky turn');
    // t1 is still inside the 4-message window on turn 3, so the all-match
    // entry keeps hitting; it leaves the window from turn 5 on.
    ck('e2e12-t3-all-still-window', sys3.includes(`${marker}-all`),
      'all-match entry lost while t1 is still in window');
    ck('e2e12-t3-any-hit', sys3.includes(`${marker}-any`),
      'any entry missing on turn 3');

    // ---- turn 4: sticky expired; cooldown-until=1+3=4 still blocks ------
    const t4Marker = `${marker}-t4`;
    await sendUserTurn(endpoint, sessionA, `霜降 ${t4Marker} 只回复 OK`);
    const sys4 = await waitTurnSystem(mock, t4Marker);
    result.details.turn4System = sys4;
    // 冷月华 stays inside the 4-text window through turn 4, so the sticky
    // keyword re-triggers and re-arms sticky-until; the entry is provided
    // as a fresh :keyword hit, not as expired :sticky carry-over.
    ck('e2e12-t4-sticky-rearmed', sys4.includes(`${marker}-sticky`),
      'sticky entry missing while 冷月华 is still in window');
    ck('e2e12-t4-cool-last-blocked', !sys4.includes(`${marker}-cool`),
      'cooldown entry leaked on turn 4');
    ck('e2e12-t4-any-hit', sys4.includes(`${marker}-any`),
      'any entry missing on turn 4');

    // ---- turn 5: cooldown expired; 霜降 retriggers -----------------------
    const t5Marker = `${marker}-t5`;
    await sendUserTurn(endpoint, sessionA, `霜降 ${t5Marker} 只回复 OK`);
    const sys5 = await waitTurnSystem(mock, t5Marker);
    result.details.turn5System = sys5;
    ck('e2e12-t5-cool-retriggered', sys5.includes(`${marker}-cool`),
      'cooldown entry did not retrigger on turn 5');
    // The 4-message window on turn 5 is [t2,t3,t4,t5]: 星轨蓝/恒星轨 have
    // left it, while 冷月华/霜降 are still present.
    ck('e2e12-t5-any-out-of-window', !sys5.includes(`${marker}-any`),
      'any entry still injected after 星轨蓝 left the window');
    ck('e2e12-t5-all-out-of-window', !sys5.includes(`${marker}-all`),
      'all entry still injected after 恒星轨 left the window');
    ck('e2e12-t5-sticky-window-hit', sys5.includes(`${marker}-sticky`),
      'sticky entry missing while 冷月华 is still in window');

    // ---- entry move to unbound book B ------------------------------------
    await mustCommand(endpoint, sessionA, ':materials/worldbook/entry/move',
      { ':entry-ids': [entryConst], ':book-id': bookB }, 'move entryConst->B');
    const snapMoved = await command(endpoint, sessionA, ':materials/snapshot', {});
    const movedEntry = receiptResult(snapMoved)[':worldbook-entry/by-id']?.[entryConst]
      || {};
    ck('e2e12-move-applied',
      (movedEntry[':book-id'] || movedEntry.bookId) === bookB,
      `entryConst book-id after move: ${movedEntry[':book-id'] || movedEntry.bookId}`);

    const t6Marker = `${marker}-t6`;
    await sendUserTurn(endpoint, sessionA, `星轨蓝 ${t6Marker} 只回复 OK`);
    const sys6 = await waitTurnSystem(mock, t6Marker);
    result.details.turn6System = sys6;
    ck('e2e12-t6-moved-entry-absent', !sys6.includes(`${marker}-const`),
      'moved constant entry still injected');
    ck('e2e12-t6-any-hit', sys6.includes(`${marker}-any`), 'any entry missing on turn 6');
    // Turn-6 window [t3,t4,t5,t6] still carries 冷月华 (sticky serves) and
    // 霜降 (cooldown-until=8 blocks the retrigger).
    ck('e2e12-t6-sticky-window-hit', sys6.includes(`${marker}-sticky`),
      'sticky entry missing while 冷月华 is still in window');
    ck('e2e12-t6-cool-blocked-again', !sys6.includes(`${marker}-cool`),
      'cooldown entry leaked on turn 6 after turn-5 retrigger');

    // Undo: move the entry back to book A — the same command the client's
    // "撤销移动" button issues with the recorded previous book ids.
    await mustCommand(endpoint, sessionA, ':materials/worldbook/entry/move',
      { ':entry-ids': [entryConst], ':book-id': bookA }, 'undo move entryConst->A');
    const snapBack = await command(endpoint, sessionA, ':materials/snapshot', {});
    const backEntry = receiptResult(snapBack)[':worldbook-entry/by-id']?.[entryConst]
      || {};
    ck('e2e12-undo-restores-book',
      (backEntry[':book-id'] || backEntry.bookId) === bookA,
      `entryConst book-id after undo: ${backEntry[':book-id'] || backEntry.bookId}`);

    const t7Marker = `${marker}-t7`;
    await sendUserTurn(endpoint, sessionA, `星轨蓝 ${t7Marker} 只回复 OK`);
    const sys7 = await waitTurnSystem(mock, t7Marker);
    result.details.turn7System = sys7;
    ck('e2e12-t7-const-restored', sys7.includes(`${marker}-const`),
      'constant entry not restored after undo');
    // Turn-7 window [t4,t5,t6,t7]: 冷月华 has left it (last seen in t2).
    // The sticky entry re-armed on t5 (its window still held t2) and its
    // sticky:2 coverage ended at t6, so t7 must NOT carry it.  霜降 is
    // still inside the window but cooldown-until=8 keeps blocking.
    ck('e2e12-t7-sticky-expired', !sys7.includes(`${marker}-sticky`),
      'sticky entry leaked past its sticky:2 coverage on turn 7');
    ck('e2e12-t7-cool-blocked-again', !sys7.includes(`${marker}-cool`),
      'cooldown entry leaked on turn 7 after turn-5 retrigger');

    // UI evidence for the worldbook page: counters + omitted rows render.
    // Point the server's presentation active session at A first: component
    // snapshots (including worldbook runtime) are projected for it, and the
    // client rejects foreign-session snapshots as cross-session.
    await focusSession(endpoint, sessionA);
    // Direct navigation first: it keeps the live session selection (A) and
    // the realtime channel.  Reload+reselect is the fallback for a stale
    // or missing materials snapshot.
    let materialsOk = await openWorldbookReady(page, bookAName, 30000);
    if (!materialsOk) {
      await page.reload({ waitUntil: 'load' });
      await page.waitForTimeout(1500);
      await waitForSync(page).catch(() => {});
      // Reload drops the client-side session selection; re-select A so the
      // materials page scopes its snapshot and counters to it.
      await openSession(page, sessionA).catch(() => {});
      materialsOk = await openWorldbookReady(page, bookAName, 90000);
    }
    if (!materialsOk) {
      result.details.materialsPageDump1 = {
        url: page.url(),
        text: (await bodyText(page)).slice(0, 4000),
      };
      throw new Error(`materials book ${bookAName} never appeared`);
    }
    const bookRow = page.locator(`.wb-list-button[data-book-id="${bookA}"]`).first();
    await bookRow.click();
    await sleep(800);
    await page.waitForFunction(
      (expected) => new RegExp(`有效用户轮次：\\s*${expected}([^0-9]|$)`)
        .test(document.body.innerText),
      7, { timeout: 30000 }).catch(() => {});
    const stickyRow = page.locator('.wb-list-button[data-entry-id]')
      .filter({ hasText: 'MR-持续' }).first();
    if (await stickyRow.count()) {
      await stickyRow.click();
      await sleep(800);
    }
    const materialsText = await bodyText(page);
    const turnRendered = /有效用户轮次：\s*(\d+)/.exec(materialsText);
    result.details.uiTurnRendered = turnRendered ? Number(turnRendered[1]) : null;
    ck('e2e12-ui-book-name-rendered', materialsText.includes(bookAName),
      'selected book name missing on materials page');
    ck('e2e12-ui-runtime-counters',
      Boolean(turnRendered) && materialsText.includes('当前会话计数')
        && materialsText.includes('最近命中轮次'),
      `runtime section: ${turnRendered ? `turn=${turnRendered[1]}` : 'missing'}`);
    ck('e2e12-ui-turn-count-7',
      turnRendered !== null && Number(turnRendered[1]) === 7,
      `expected rendered turn 7 after t1-t7, got ${turnRendered && turnRendered[1]}`);
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e12-04-materials-worldbook'));

    // ---- session B: isolation -------------------------------------------
    await openSession(page, sessionB);
    await focusSession(endpoint, sessionB);
    const tB1 = `${marker}-b1`;
    await sendUserTurn(endpoint, sessionB, `星轨蓝 冷月华 霜降 ${tB1} 只回复 OK`);
    const sysB1 = await waitTurnSystem(mock, tB1);
    result.details.turnB1System = sysB1;
    ck('e2e12-b1-fresh-sticky', sysB1.includes(`${marker}-sticky`),
      'session B sticky entry missing');
    ck('e2e12-b1-fresh-cool', sysB1.includes(`${marker}-cool`),
      'session B cool entry missing');
    ck('e2e12-b1-no-turn-card', !sysB1.includes(`${marker}-card-turn`),
      'turn card leaked into session B');
    ck('e2e12-b1-no-session-card', !sysB1.includes(`${marker}-card-session`),
      'session A card leaked into session B');
    ck('e2e12-b1-budget-omitted', !sysB1.includes(`${marker}-budget-`),
      'budget entry provided in session B');

    await waitFrozenPanel(page);
    const panelB = await openPanel(page);
    result.details.panelB = panelB.slice(0, 6000);
    ck('e2e12-panelB-fresh',
      !panelB.includes('持续提供中') && !panelB.includes('冷却中'),
      'session B panel shows stale counters');
    ck('e2e12-panelB-budget-omitted',
      panelB.includes('命中但未提供') && panelB.includes(entryBudget)
        && panelB.includes('超出本轮预算'),
      panelB.slice(0, 3000));
    ck('e2e12-panelB-no-cards',
      !panelB.includes(`MR-仅本轮卡-${stamp}`)
        && !panelB.includes(`MR-会话卡-${stamp}`),
      'session B panel lists session A cards');
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e12-05-session-b'));

    // ---- session A: clear the session card -------------------------------
    await mustCommand(endpoint, sessionA, ':materials/instruction/session/clear',
      {}, 'clear session cards');
    await openSession(page, sessionA);
    await focusSession(endpoint, sessionA);
    const t8Marker = `${marker}-t8`;
    await sendUserTurn(endpoint, sessionA, `${t8Marker} 只回复 OK`);
    const sys8 = await waitTurnSystem(mock, t8Marker);
    result.details.turn8System = sys8;
    ck('e2e12-t8-session-card-cleared', !sys8.includes(`${marker}-card-session`),
      'session card still injected after clear');
    ck('e2e12-t8-turn-card-still-gone', !sys8.includes(`${marker}-card-turn`),
      'turn card resurrected on turn 8');
    ck('e2e12-t8-const-still-provided', sys8.includes(`${marker}-const`),
      'constant entry missing after card clear');

    await waitFrozenPanel(page);
    const panel8 = await openPanel(page);
    result.details.panel8 = panel8.slice(0, 6000);
    ck('e2e12-panel8-no-cards',
      !panel8.includes(`MR-仅本轮卡-${stamp}`)
        && !panel8.includes(`MR-会话卡-${stamp}`),
      'cleared cards still listed in panel');

    // ---- UI: rendered turn counter + undo-move button path ---------------
    // The materials page targets the selected session; session A is still
    // selected with 8 accepted user turns.
    // Keep the server presentation session on A so the pushed materials
    // snapshot carries session A's worldbook runtime.
    await focusSession(endpoint, sessionA);
    // Direct-first, reload-fallback — same pattern as the earlier check.
    let materialsOk2 = await openWorldbookReady(page, bookAName, 30000);
    if (!materialsOk2) {
      await page.reload({ waitUntil: 'load' });
      await page.waitForTimeout(1500);
      await waitForSync(page).catch(() => {});
      await openSession(page, sessionA).catch(() => {});
      materialsOk2 = await openWorldbookReady(page, bookAName, 90000);
    }
    if (!materialsOk2) {
      result.details.materialsPageDump2 = {
        url: page.url(),
        text: (await bodyText(page)).slice(0, 4000),
      };
      throw new Error(`materials book ${bookAName} never appeared (second pass)`);
    }
    const uiBookRow = page.locator(`.wb-list-button[data-book-id="${bookA}"]`).first();
    await uiBookRow.click();
    await sleep(800);
    await page.waitForFunction(
      (expected) => new RegExp(`有效用户轮次：\\s*${expected}([^0-9]|$)`)
        .test(document.body.innerText),
      8, { timeout: 30000 }).catch(() => {});
    const matText8 = await bodyText(page);
    const turn8Rendered = /有效用户轮次：\s*(\d+)/.exec(matText8);
    result.details.uiTurnRendered8 = turn8Rendered ? Number(turn8Rendered[1]) : null;
    ck('e2e12-ui-turn-count-8',
      turn8Rendered !== null && Number(turn8Rendered[1]) === 8,
      `expected rendered turn 8, got ${turn8Rendered && turn8Rendered[1]}`);

    // Drive the move through the UI so the client's :last-move undo data is
    // populated; control-channel moves never pass through the outbox.
    const entryBookId = async () => {
      const snap = await command(endpoint, sessionA, ':materials/snapshot', {});
      const entry = receiptResult(snap)[':worldbook-entry/by-id']?.[entryConst] || {};
      return entry[':book-id'] || entry.bookId || null;
    };
    const uiEntryBtn = page.locator(
      `.wb-list-button[data-entry-id="${entryConst}"]`).first();
    await uiEntryBtn.click();
    await sleep(800);
    const moveSelect = page.locator('label.hc-mat-field', { hasText: '移动到' })
      .locator('select').first();
    await moveSelect.selectOption({ label: bookBName });
    await sleep(300);
    await page.getByRole('button', { name: '移动', exact: true }).click();

    let uiBook = null;
    const moveDeadline = Date.now() + 60000;
    while (Date.now() < moveDeadline) {
      uiBook = await entryBookId().catch(() => null);
      if (uiBook === bookB) break;
      await sleep(500);
    }
    ck('e2e12-ui-move-applied', uiBook === bookB,
      `entryConst book after UI move: ${uiBook}`);

    // The accepted move receipt resets the entry draft, so the undo button
    // only renders once the moved entry is re-selected under book B.
    const uiBookBRow = page.locator(`.wb-list-button[data-book-id="${bookB}"]`).first();
    await uiBookBRow.click();
    await sleep(800);
    const movedEntryBtn = page.locator(
      `.wb-list-button[data-entry-id="${entryConst}"]`).first();
    await movedEntryBtn.click();
    await sleep(800);
    const undoBtn = page.getByRole('button', { name: '撤销移动', exact: true });
    const undoVisible = await undoBtn.waitFor({ state: 'visible', timeout: 30000 })
      .then(() => true).catch(() => false);
    ck('e2e12-ui-undo-button-visible', undoVisible,
      '撤销移动 button did not render for the UI-issued move');

    if (undoVisible) {
      await undoBtn.click();
      let undone = null;
      const undoDeadline = Date.now() + 60000;
      while (Date.now() < undoDeadline) {
        undone = await entryBookId().catch(() => null);
        if (undone === bookA) break;
        await sleep(500);
      }
      ck('e2e12-ui-undo-restores-book', undone === bookA,
        `entryConst book after UI undo: ${undone}`);
    }
    result.screenshots.push(await shot(page, EVIDENCE, 'e2e12-06-materials-undo'));

    // ---- worldbook runtime counters via snapshot -------------------------
    const snapA = await command(endpoint, sessionA, ':materials/snapshot', {});
    const wbSession = receiptResult(snapA)[':worldbook-session'] || {};
    const turnCount = wbSession[':turn'] ?? wbSession.turn;
    result.details.worldbookSessionA = {
      turn: turnCount,
      runtimeKeys: Object.keys(wbSession[':runtime'] || wbSession.runtime || {}),
    };
    // 8 accepted user turns on session A -> worldbook turn counter = 8.
    ck('e2e12-wb-turn-counter', Number(turnCount) === 8,
      `expected worldbook turn 8, got ${turnCount}`);
    const runtime = wbSession[':runtime'] || wbSession.runtime || {};
    const coolRuntime = runtime[entryCool] || {};
    ck('e2e12-cooldown-runtime-recorded',
      Number(coolRuntime[':cooldown-until'] ?? coolRuntime.cooldownUntil) >= 8,
      `cooldown-until: ${JSON.stringify(coolRuntime)}`);

    result.ok = Object.values(result.checks).every((v) => v.pass);
    result.finishedAt = new Date().toISOString();
    evidence('e2e12-result', result);
    const failedChecks = Object.entries(result.checks)
      .filter(([, v]) => !v.pass).map(([k]) => k);
    assert(result.ok, `one or more E2E 12 checks failed: ${JSON.stringify(failedChecks)}`);
  } catch (error) {
    result.ok = false;
    result.error = { message: error.message, stack: String(error.stack).slice(0, 3000) };
    result.finishedAt = new Date().toISOString();
    try {
      result.screenshots.push(await shot(page, EVIDENCE, 'e2e12-error'));
    } catch { /* ignore */ }
    evidence('e2e12-result', result);
    throw error;
  } finally {
    await app.close();
    await mock.close();
  }
});
