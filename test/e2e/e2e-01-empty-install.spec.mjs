// E2E 01: empty install to the first savable reply.
//
// This test intentionally exercises the new-connection form.  The typed id
// `manual-only` is deliberately absent from the mock's model listing, which
// reproduces D-12: a manually added model cannot be confirmed by a listing.
// The fix marks it `:not-returned` and lets `:unknown` send, showing
// `尚未确认` instead of disabling the composer.
//
// The capability save used to be trusted from the optimistic row badge: the
// settings row renders the draft's declaration before the server receipt, so
// "工具：已声明支持" also appeared when the save was never committed.  The
// spec now waits for the receipt sentence `连接已保存；草稿已清理。` and then
// re-reads the model row from a fresh BrowserContext, whose settings state
// comes only from the server snapshot.  When the send still fails, the raw
// WebSocket frames are scanned for the refused receipt's
// `error.details.capabilities-source` value, which distinguishes a live-model
// declaration from a binding-only one.
import { test } from '@playwright/test';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { startApp, REPO_ROOT } from './support/app.mjs';
import {
  startMocks, buildAppOptions, verifyFixtureKeysAgainstServerSource,
  summarizeChecks,
} from './support/fixtures.mjs';
import { sourceTreeSha256 } from './support/evidence.mjs';
import {
  assert, assertEqual, composer, dismissSettingsMessages, evidenceDir, goto,
  newSession,
  sessionModelLabel, settingsAddLocalConnection, settingsEnableModelAndBindMain,
  shot, userTexts, waitForAssistant, waitForShell, waitForSync, waitForSettings,
  writeJson, assistantTexts, selectFirstSession, lastAssistantText,
} from './support/ui.mjs';

test.describe.configure({ mode: 'serial' });

// Both journeys share one mock model instance: the OAuth test must be able to
// distinguish its own chat request from the manual-model test's, and the model
// listing/model ids stay identical.
let mocks;

test.beforeAll(async () => {
  mocks = await startMocks({ model: true, oauth: true });
});

test.afterAll(async () => {
  if (mocks) await mocks.close();
});

// Frame tap for the Sente WebSocket.  The client keeps command receipts in
// Fulcro state that has no public probe; the receipt still crosses the wire,
// and the refused send carries `error.details.capabilities-source`.  Only the
// one diagnostic key is extracted; frames are never persisted wholesale.
function watchWebSocketFrames(page) {
  const frames = [];
  const bindings = [];
  page.on('websocket', (socket) => {
    const onFrame = (event) => {
      const payload = typeof event.payload === 'string'
        ? event.payload
        : Buffer.from(event.payload).toString('utf8');
      frames.push(payload);
      if (frames.length > 500) frames.shift();
    };
    socket.on('framereceived', onFrame);
    bindings.push([socket, onFrame]);
  });
  return {
    frames,
    stop() {
      for (const [socket, onFrame] of bindings) {
        socket.off('framereceived', onFrame);
      }
    },
  };
}

function capabilitiesSourceFromFrames(frames) {
  // Transit/JSON encodes keywords as `"~:name"`; a receipt map renders
  // `..., "~:capabilities-source", "~:none", ...`.  Scanning for this one
  // pair avoids implementing a general transit reader in the spec.
  const pattern = /~:capabilities-source\\?"\s*,\s*"~:([A-Za-z_-]+)"/;
  for (let index = frames.length - 1; index >= 0; index -= 1) {
    const match = pattern.exec(frames[index]);
    if (match) return match[1];
  }
  return null;
}

// Poll the frames that have already arrived instead of guessing a fixed
// receipt latency.  The 100 ms sleep is only the poll interval; the real
// condition is the `capabilities-source` keyword pair.
async function waitForCapabilitiesSource(page, frames, timeout = 5000) {
  const started = Date.now();
  for (;;) {
    const source = capabilitiesSourceFromFrames(frames);
    if (source) return source;
    if (Date.now() - started >= timeout) return null;
    await page.waitForTimeout(100);
  }
}

async function settingsMessages(page) {
  return page.locator('.hc-message').allInnerTexts();
}

// The settings message banner is a single slot: a later role save or model
// switch replaces the connection receipt before the helper returns.  Record
// every banner text that reaches the DOM so the receipt is still evidence.
async function startSettingsMessageLog(page) {
  await page.evaluate(() => {
    window.__hcSettingsMessages = [];
    const record = () => {
      for (const node of document.querySelectorAll('.hc-message')) {
        const text = (node.textContent || '').trim();
        if (text && !window.__hcSettingsMessages.includes(text)) {
          window.__hcSettingsMessages.push(text);
        }
      }
    };
    record();
    window.__hcSettingsMessagesObserver?.disconnect();
    window.__hcSettingsMessagesObserver =
      new MutationObserver(record);
    window.__hcSettingsMessagesObserver.observe(document.body, {
      childList: true, subtree: true, characterData: true,
    });
  });
}

async function settingsMessageLog(page) {
  return page.evaluate(() => window.__hcSettingsMessages || []);
}

// Wait until the settings reducer accepted a connection save.  The receipt
// is the only observable state that proves the server committed the draft;
// the row badge alone is derived from the local draft.  The fallback accepts
// an older server copy that clears the draft without the receipt sentence.
async function waitForConnectionSaveReceipt(page, timeout = 45000) {
  const committed = await page.waitForFunction(() => {
    const messages = [...document.querySelectorAll('.hc-message')]
      .map((el) => (el.textContent || ''));
    return messages.some((text) => text.includes('连接已保存')
      && (text.includes('草稿已清理')
          || text.includes('服务器已保存提交版本')));
  }, null, { timeout }).then(() => true).catch(() => false);
  if (committed) return 'receipt';
  // Fallback: the draft left the editor.  `apply-save-connection` only drops
  // the draft when the receipt digest matches the submitted digest.
  await page.getByRole('heading', { name: '连接编辑', exact: true }).first()
    .waitFor({ state: 'visible', timeout });
  return 'draft-cleared';
}

// Read the server-committed model row through a new BrowserContext.  Settings
// state in a fresh context has no persisted draft, so the capability text is
// the server snapshot or nothing.
async function committedCapabilities(browser, appUrl, dir, options) {
  const { modelId, connectionName, shotName } = options;
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
  });
  try {
    const page = await context.newPage();
    await goto(page, appUrl, '/settings');
    await waitForSettings(page);
    await page.getByRole('button', { name: '模型与角色', exact: true }).first().click();
    const group = page.locator('.hc-model-group').filter({ hasText: connectionName }).first();
    await group.waitFor({ state: 'visible', timeout: 30000 });
    const row = page.locator('.hc-model-row').filter({ hasText: modelId }).first();
    await row.waitFor({ state: 'visible', timeout: 30000 });
    // The settings publisher can project the save receipt slightly after the
    // row is mountable.  Poll the fresh-context row for at most 10s instead
    // of deciding from one early snapshot.
    const deadline = Date.now() + 10000;
    let rowText = '';
    let toolsAria = null;
    let saved = false;
    for (;;) {
      const snapshot = await row.evaluate((el) => {
        const badge = el.querySelector('[aria-label^="工具："]');
        return {
          rowText: el.innerText || '',
          toolsAria: badge ? badge.getAttribute('aria-label') : null,
        };
      });
      rowText = snapshot.rowText;
      toolsAria = snapshot.toolsAria;
      saved = rowText.includes('工具：已声明支持')
        && !rowText.includes('工具：尚未确认');
      if (saved || Date.now() >= deadline) break;
      await page.waitForTimeout(500);
    }
    await shot(page, dir, shotName);
    return {
      rowText: rowText.slice(0, 1500),
      toolsAria,
      userSource: typeof toolsAria === 'string' && toolsAria.includes('用户设置'),
      saved,
    };
  } finally {
    await context.close();
  }
}

test('@core E2E 01 empty install -> first savable reply', async ({ page, browser }) => {
  test.setTimeout(420000);
  const dir = evidenceDir('e2e01');
  const mock = mocks.model;
  const app = await startApp({ evidenceDir: dir });
  const wsFrames = watchWebSocketFrames(page);
  const browserEvents = [];
  const logBrowserEvent = (type, detail = '') => {
    const line = `${new Date().toISOString()} ${type}${detail ? ` ${detail}` : ''}`;
    browserEvents.push(line);
    fs.appendFileSync(path.join(dir, 'browser-events.log'), `${line}\n`);
  };
  page.on('crash', () => logBrowserEvent('crash'));
  page.on('close', () => logBrowserEvent('close'));
  page.on('pageerror', (error) => logBrowserEvent('pageerror', String(error).slice(0, 500)));
  page.on('console', (message) => {
    if (message.type() === 'error') logBrowserEvent('console-error', message.text().slice(0, 500));
  });
  browser.on('disconnected', () => logBrowserEvent('browser-disconnected'));
  const marker = `E2E01-${Date.now()}`;
  const draft = `草稿 ${marker}：先写下来，再配置模型。`;
  const reply = `Mock 回复 ${marker}：这条回答可以复制。`;
  const result = {
    test: 'E2E 01',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    mockUrl: mock.url,
    chromeVersion: browser.version(),
    clientBundle: path.relative(REPO_ROOT, path.join(REPO_ROOT, 'resources/public/js/main.js')),
    clientBundleSha256: crypto.createHash('sha256')
      .update(fs.readFileSync(path.join(REPO_ROOT, 'resources/public/js/main.js')))
      .digest('hex'),
    sourceTreeSha256: sourceTreeSha256(),
    checks: {},
    screenshots: [],
    browserEvents,
  };
  const finalize = () => {
    const summary = summarizeChecks(result.checks);
    result.failedChecks = summary.failedChecks;
    result.ok = summary.ok;
    return summary.ok;
  };
  try {
    await fetch(`${mock.url}/__mock/config`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ reply }),
    });

    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    result.screenshots.push(await shot(page, dir, 'e2e01-01-empty-sessions'));

    await newSession(page);
    const input = composer(page);
    await input.fill(draft);
    assertEqual(await input.inputValue(), draft, 'draft is not editable before model setup');
    const send = page.locator('button[aria-label="发送消息"]').first();
    result.sendDisabledBeforeModel = await send.isDisabled();
    result.screenshots.push(await shot(page, dir, 'e2e01-02-draft-before-model'));

    await goto(page, app.url, '/settings');
    await waitForSettings(page);
    const settingsText = await page.locator('body').innerText();
    result.checks.onboardingNoRegistration = settingsText.includes('无需注册本产品云账号');
    result.checks.onboardingSaysDraftSurvives = settingsText.includes('草稿在进出设置页后保留');
    assert(result.checks.onboardingNoRegistration, 'onboarding does not say registration is unnecessary');
    assert(result.checks.onboardingSaysDraftSurvives, 'onboarding does not promise draft survival');
    result.screenshots.push(await shot(page, dir, 'e2e01-03-onboarding'));

    await settingsAddLocalConnection(page, {
      name: 'Mock 本地服务',
      baseUrl: `${mock.url}/v1`,
      modelId: 'manual-only',
    });
    const afterSave = await page.locator('body').innerText();
    result.checks.serviceIdentityVisible = afterSave.includes('Mock 本地服务');
    result.checks.serviceHostVisible = afterSave.includes(`127.0.0.1:${mock.port}`);
    result.checks.serviceKindVisible = afterSave.includes('本地模型服务');
    assert(result.checks.serviceIdentityVisible, 'saved connection name is not visible');
    assert(result.checks.serviceHostVisible, 'saved connection actual host is not visible');
    assert(result.checks.serviceKindVisible, 'saved connection service kind is not visible');
    result.screenshots.push(await shot(page, dir, 'e2e01-04-connection-saved'));

    // Do not let the first connection save's banner become evidence for the
    // later capability save.  Clear it before starting the receipt log.
    await dismissSettingsMessages(page);
    await startSettingsMessageLog(page);
    const bind = await settingsEnableModelAndBindMain(page, {
      modelId: 'manual-only',
      connectionName: 'Mock 本地服务',
      declareTools: true,
    });
    result.bind = bind;
    result.screenshots.push(await shot(page, dir, 'e2e01-05-model-bound'));

    // Independent proof that the tool declaration was committed, not merely
    // rendered from the still-open draft.
    const messageLog = await settingsMessageLog(page);
    const receiptText = messageLog.find((text) => text.includes('连接已保存')
      && (text.includes('草稿已清理')
          || text.includes('服务器已保存提交版本')));
    result.bind.saveReceiptLog = messageLog;
    result.bind.saveReceipt = receiptText || null;
    result.bind.savePath = receiptText
      ? 'receipt'
      : await waitForConnectionSaveReceipt(page);
    result.bind.saveReceipts = await settingsMessages(page);
    const committed = await committedCapabilities(browser, app.url, dir, {
      modelId: 'manual-only',
      connectionName: 'Mock 本地服务',
      shotName: 'e2e01-05b-committed-tools',
    });
    result.bind.serverCommitted = committed;
    result.checks.toolsDeclaredOnServer = committed.saved;
    result.bind.toolsDeclared = bind.toolsDeclared || committed.saved;
    assert(committed.saved,
      `server snapshot does not declare tools for manual-only after the save receipt: ${committed.rowText.slice(0, 500)}`);

    await goto(page, app.url, '/sessions');
    await waitForShell(page);
    await selectFirstSession(page);
    const composerAfterSettings = await composer(page).inputValue();
    assertEqual(composerAfterSettings, draft, 'draft was lost after visiting settings');
    result.checks.draftSurvivedSettings = composerAfterSettings === draft;
    const modelLabel = await sessionModelLabel(page);
    result.modelLabel = modelLabel;
    result.checks.sessionModelVisible = modelLabel.includes('manual-only');
    result.checks.sessionModelSwitchAccepted = bind.switchStatus === 'clicked';
    result.screenshots.push(await shot(page, dir, 'e2e01-06-draft-restored'));
    if (!result.checks.sessionModelVisible
        || !result.checks.sessionModelSwitchAccepted) {
      result.knownDefect = 'D-12';
      result.blockedNotice = `手动模型未能绑定到当前会话：用于下一轮=${bind.switchStatus}；模型标签=${modelLabel}`;
      result.ok = finalize();
      result.finishedAt = new Date().toISOString();
      result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
      result.evidence = writeJson(dir, 'e2e01-result', result);
      await shot(page, dir, 'e2e01-07-bind-blocked-d12');
      throw new Error(`KNOWN DEFECT D-12: ${result.blockedNotice}`);
    }

    await composer(page).fill(draft);
    const sendButton = page.locator('button[aria-label="发送消息"]').first();
    let blockedNotice = '';
    if (await sendButton.isDisabled()) {
      const notices = await page.locator('.hc-message--warning, .hc-message--error, [role="alert"]')
        .allInnerTexts().catch(() => []);
      blockedNotice = notices.find((text) => text.includes('不可用') || text.includes('草稿已保留')) || '';
      if (!blockedNotice) {
        blockedNotice = `发送按钮禁用；模型标签=${modelLabel}；绑定状态=${bind.switchStatus}`;
      }
    }
    result.checks.sendAllowed = !blockedNotice;
    if (blockedNotice) {
      result.knownDefect = 'D-12';
      result.blockedNotice = blockedNotice;
      result.sendFailure = {
        capabilitiesSource: capabilitiesSourceFromFrames(wsFrames.frames),
        wsFramesCaptured: wsFrames.frames.length,
        blockedByD12: true,
      };
      result.ok = finalize();
      result.finishedAt = new Date().toISOString();
      result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
      result.evidence = writeJson(dir, 'e2e01-result', result);
      await shot(page, dir, 'e2e01-07-send-blocked-d12');
      throw new Error(`KNOWN DEFECT D-12: send was blocked after enabling a manually added model: ${blockedNotice}`);
    }

    await sendButton.click();
    const userCreated = await page.waitForFunction(
      () => document.querySelectorAll('.hc-message-out').length > 0,
      null,
      { timeout: 15000 },
    ).then(() => true).catch(() => false);
    result.sendUserMessageCreated = userCreated;
    result.checks.sendCreatedUserMessage = userCreated;
    if (!userCreated) {
      const notices = await page.locator('.hc-message--warning, .hc-message--error, [role="alert"]')
        .allInnerTexts().catch(() => []);
      // The refused receipt crosses the WebSocket asynchronously; poll the
      // received frames for the diagnostic pair instead of guessing a delay.
      const source = await waitForCapabilitiesSource(page, wsFrames.frames);
      result.sendNotices = notices;
      result.sendFailure = {
        capabilitiesSource: source,
        wsFramesCaptured: wsFrames.frames.length,
      };
      result.bodyAfterSend = (await page.locator('body').innerText()).slice(0, 3000);
      result.errorStep = 'send-no-user-message';
      result.ok = finalize();
      result.finishedAt = new Date().toISOString();
      result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
      result.evidence = writeJson(dir, 'e2e01-result', result);
      await shot(page, dir, 'e2e01-07-send-no-user-message');
      throw new Error(`send did not create a user message; capabilities-source=${source}; notices=${notices.join(' | ')}`);
    }
    let replyResult;
    try {
      replyResult = await waitForAssistant(page, {
        timeout: 60000,
        minChars: reply.length,
        quietMs: 3000,
      });
    } catch (error) {
      result.errorStep = 'assistant-timeout';
      result.bodyAfterSend = (await page.locator('body').innerText()).slice(0, 3000);
      throw error;
    }
    result.reply = replyResult.text;
    result.checks.replyReceived = replyResult.text.includes(marker);
    assert(result.checks.replyReceived, `reply does not contain marker: ${replyResult.text.slice(0, 160)}`);
    const bodyAfterSend = await page.locator('body').innerText();
    result.checks.noToolRefusalNotice =
      !/does not declare tool support|未声明工具支持/.test(bodyAfterSend);
    assert(result.checks.noToolRefusalNotice,
      'a tool-requirement refusal is still rendered after a successful send');

    const users = await userTexts(page);
    result.userMessages = users;
    result.checks.singleUserMessage = users.length === 1;
    result.checks.userMessageHasDraft = users.length === 1 && users[0].includes(marker);
    assert(result.checks.singleUserMessage, `expected one user message, got ${users.length}`);
    assert(result.checks.userMessageHasDraft, 'user message does not contain the draft');

    const assistants = await assistantTexts(page);
    result.assistantMessages = assistants;
    result.checks.singleAssistantReply = assistants.length === 1;
    assert(result.checks.singleAssistantReply, `expected one assistant reply, got ${assistants.length}`);

    const copyButton = page.locator('.hc-message-in:not(.hc-message-process)')
      .last().locator('.hc-message-actions button', { hasText: '复制' }).first();
    await copyButton.click();
    const clipboard = await page.evaluate(() => navigator.clipboard.readText());
    result.clipboard = clipboard;
    result.checks.replyCopyable = clipboard.includes(marker);
    assert(result.checks.replyCopyable, `clipboard does not contain reply: ${clipboard.slice(0, 120)}`);
    result.screenshots.push(await shot(page, dir, 'e2e01-07-reply-copied'));

    await page.reload({ waitUntil: 'load' });
    await waitForShell(page);
    await waitForSync(page);
    if (!(await composer(page).count())) await selectFirstSession(page);
    const reloadText = await page.locator('body').innerText();
    const reloadAssistants = await assistantTexts(page);
    const reloadModel = await sessionModelLabel(page);
    result.reloadModelLabel = reloadModel;
    result.checks.reloadKeepsUser = reloadText.includes(marker);
    result.checks.reloadKeepsReply = reloadAssistants.some((text) => text.includes(marker));
    result.checks.reloadKeepsModel = reloadModel.includes('manual-only');
    assert(result.checks.reloadKeepsUser, 'user message lost after reload');
    assert(result.checks.reloadKeepsReply, 'reply lost after reload');
    assert(result.checks.reloadKeepsModel, 'model attribution lost after reload');
    result.screenshots.push(await shot(page, dir, 'e2e01-08-reload'));

    const context2 = await browser.newContext({
      viewport: { width: 1440, height: 1000 },
      permissions: ['clipboard-read', 'clipboard-write'],
    });
    try {
      const page2 = await context2.newPage();
      await goto(page2, app.url, '/sessions');
      await waitForSync(page2);
      await selectFirstSession(page2);
      const reopenText = await page2.locator('body').innerText();
      const reopenModel = await sessionModelLabel(page2);
      result.reopenModelLabel = reopenModel;
      result.checks.reopenKeepsUser = reopenText.includes(marker);
      result.checks.reopenKeepsReply = reopenText.includes(marker);
      result.checks.reopenKeepsModel = reopenModel.includes('manual-only');
      assert(result.checks.reopenKeepsUser, 'user message lost in a new browser context');
      assert(result.checks.reopenKeepsReply, 'reply lost in a new browser context');
      assert(result.checks.reopenKeepsModel, 'model attribution lost in a new browser context');
      result.screenshots.push(await shot(page2, dir, 'e2e01-09-reopen'));
    } finally {
      await context2.close();
    }

    result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json();
    result.checks.mockWasUsed = result.mockState.chatCompletions >= 1;
    assert(result.checks.mockWasUsed, 'the mock model was not called');
    const toolRequests = (result.mockState.log || []).filter((entry) =>
      entry.hasTools === true && String(entry.user || '').includes(marker));
    result.toolRequests = toolRequests.slice(-3);
    result.checks.toolDeclarationReachedModel = toolRequests.length >= 1;
    assert(result.checks.toolDeclarationReachedModel,
      'the send request carried no tools; the saved capability never reached the model');
    result.ok = finalize();
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e01-result', result);
    assert(result.ok, 'one or more E2E 01 checks failed');
  } catch (error) {
    result.ok = false;
    result.failedChecks =
      (result.failedChecks && result.failedChecks.length)
        ? result.failedChecks
        : ['run-aborted'];
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
    result.evidence = writeJson(dir, 'e2e01-result', result);
    throw error;
  } finally {
    wsFrames.stop();
    await app.close();
  }
});

test('@core E2E 01 OAuth device login -> declared tools -> reply', async ({ page, browser }) => {
  test.setTimeout(420000);
  const dir = evidenceDir('e2e01-oauth');
  const mock = mocks.model;
  const oauth = mocks.oauth;
  const marker = `E2E01-OAUTH-${Date.now()}`;
  const reply = `Mock OAuth 回复 ${marker}：账号连接可用。`;

  await fetch(`${mock.url}/__mock/config`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ reply }),
  });

  // No preconfigured models: the session may only send after the OAuth
  // connection is saved and its model is declared, so a missing commit cannot
  // fall back to a startup role binding.
  const appOptions = buildAppOptions(mocks, {
    evidenceDir: dir,
    models: { connections: [], roles: {} },
  });
  // The provider's default `:base-url` points at the OAuth mock's `/v1`, which
  // has no chat route.  The model endpoint is the mock model's `/v1`.
  appOptions.modelAuth.providers.local.baseUrl = `${mock.url}/v1`;
  const app = await startApp(appOptions);

  const result = {
    test: 'E2E 01 OAuth',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    mockUrl: mock.url,
    oauthUrl: oauth.url,
    checks: {},
    screenshots: [],
  };
  const finalize = () => {
    const summary = summarizeChecks(result.checks);
    result.failedChecks = summary.failedChecks;
    result.ok = summary.ok;
    return summary.ok;
  };
  try {
    // Unit-level fixture guard: the four `?` keys must match the server
    // schemas that consume them, or the server rejects the whole section at
    // startup (the failure only appears as an unexplained config error).
    const fixtureKeys = verifyFixtureKeysAgainstServerSource();
    result.fixtureKeys = fixtureKeys;
    result.checks.fixtureKeysAligned = fixtureKeys.ok;

    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);

    await goto(page, app.url, '/settings');
    await waitForSettings(page);
    await page.getByRole('button', { name: '使用账号登录', exact: true }).first().click();
    const editor = page.locator('section.hc-panel')
      .filter({ hasText: '新建连接：' }).first();
    await editor.waitFor({ state: 'visible', timeout: 30000 });
    result.checks.accountLoginDraftOpened = (await editor.count()) >= 1;
    await page.locator('.hc-field', { hasText: '显示名称' }).locator('input').first()
      .fill('Mock OAuth');

    const authPanel = page.locator('.hc-model-auth').first();
    await authPanel.waitFor({ state: 'visible', timeout: 30000 });
    const flowSelect = page.locator('.hc-field', { hasText: '登录方式' })
      .locator('select').first();
    await flowSelect.waitFor({ state: 'visible', timeout: 30000 });
    await flowSelect.selectOption('device-code');
    // React applies a controlled <select> value on the next render.  Wait for
    // the DOM value itself (bounded); do not read the pre-render value and do
    // not use a fixed sleep.
    await page.waitForFunction(() => {
      const fields = [...document.querySelectorAll('.hc-field')];
      const field = fields.find((node) =>
        (node.textContent || '').includes('登录方式'));
      const select = field && field.querySelector('select');
      return Boolean(select && select.value === 'device-code');
    }, null, { timeout: 15000 }).catch(() => {});
    const selectValue = await flowSelect.inputValue().catch(() => null);
    const selectOptions = await flowSelect.locator('option')
      .evaluateAll((options) => options.map((option) => ({
        value: option.value,
        text: (option.textContent || '').trim(),
      })))
      .catch(() => []);
    result.diagnostics = {
      ...(result.diagnostics || {}),
      deviceFlowSelectValue: selectValue,
      deviceFlowSelectOptions: selectOptions,
    };
    result.checks.deviceFlowSelected = selectValue === 'device-code';
    await page.getByRole('button', { name: '开始账号登录', exact: true }).first().click();

    const codeElement = page.locator('.hc-model-auth__code code').first();
    await codeElement.waitFor({ state: 'visible', timeout: 60000 });
    const userCode = (await codeElement.innerText()).trim();
    result.userCode = userCode;
    assert(/^MOCK-[0-9A-F]+$/i.test(userCode), `unexpected device code: ${userCode}`);
    await shot(page, dir, 'e2e01-oauth-01-device-code');

    // The operator approves the same code on the mock's confirmation page.
    const confirm = await fetch(`${oauth.url}/device/confirm`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ user_code: userCode, decision: 'approve' }).toString(),
    });
    assertEqual(confirm.status, 200, 'mock OAuth device confirmation failed');

    const connectedHeading = page.locator('.hc-model-auth--authorized h3',
      { hasText: '账号已连接' }).first();
    await connectedHeading.waitFor({ state: 'visible', timeout: 90000 });
    result.checks.oauthAccountConnected = (await connectedHeading.count()) >= 1;
    result.oauthState = await (await fetch(`${oauth.url}/__mock/state`)).json();
    result.checks.oauthDeviceAuthorizationRequested =
      (result.oauthState.requests || [])
        .some((request) => request.path === '/device_authorization');
    const authDraft = await page.locator('.hc-auth-status').first().innerText();
    result.checks.draftHasAuthReference = /草稿认证引用/.test(authDraft);
    result.authDraftText = authDraft.slice(0, 600);
    assert(result.checks.draftHasAuthReference,
      `authorized account did not patch the draft: ${authDraft.slice(0, 300)}`);
    assert(result.checks.oauthDeviceAuthorizationRequested,
      `the OAuth mock saw no device_authorization request: ${JSON.stringify(result.oauthState.requests)}`);
    await shot(page, dir, 'e2e01-oauth-02-account-connected');

    // The server never lists a remote provider's models into the draft; the
    // supported path is the manual model id, which the mock model accepts.
    const modelInput = page.locator('.hc-manual-model input').first();
    await modelInput.fill('mock-fast');
    await page.waitForFunction(() => {
      const input = document.querySelector('.hc-manual-model input');
      return Boolean(input && input.value === 'mock-fast');
    }, null, { timeout: 5000 });
    assertEqual(await modelInput.inputValue(), 'mock-fast',
      'manual model input did not accept the id');
    await page.getByRole('button', { name: '加入模型', exact: true }).first().click();
    await page.locator('.hc-chip', { hasText: 'mock-fast' }).first()
      .waitFor({ state: 'visible', timeout: 10000 });
    await page.locator('.hc-draft-footer')
      .getByRole('button', { name: '保存连接', exact: true }).first().click();
    result.oauthSavePath = await waitForConnectionSaveReceipt(page);
    result.saveReceipts = await settingsMessages(page);
    const card = page.locator('.hc-connection-card').filter({ hasText: 'Mock OAuth' }).first();
    await card.waitFor({ state: 'visible', timeout: 30000 });
    result.checks.oauthConnectionSaved = (await card.count()) >= 1;
    await shot(page, dir, 'e2e01-oauth-03-connection-saved');

    // Model list: the saved connection's configured model row must appear.
    await page.getByRole('button', { name: '模型与角色', exact: true }).first().click();
    const group = page.locator('.hc-model-group').filter({ hasText: 'Mock OAuth' }).first();
    await group.waitFor({ state: 'visible', timeout: 30000 });
    await group.getByRole('button', { name: '刷新模型列表', exact: true }).first().click();
    const listedRow = page.locator('.hc-model-row').filter({ hasText: 'mock-fast' }).first();
    await listedRow.waitFor({ state: 'visible', timeout: 60000 });
    result.checks.oauthModelsListed = (await listedRow.count()) >= 1;

    await dismissSettingsMessages(page);
    await startSettingsMessageLog(page);
    const bind = await settingsEnableModelAndBindMain(page, {
      modelId: 'mock-fast',
      connectionName: 'Mock OAuth',
      declareTools: true,
    });
    result.bind = bind;
    const messageLog = await settingsMessageLog(page);
    const receiptText = messageLog.find((text) => text.includes('连接已保存')
      && (text.includes('草稿已清理')
          || text.includes('服务器已保存提交版本')));
    result.bind.saveReceiptLog = messageLog;
    result.bind.saveReceipt = receiptText || null;
    result.bind.savePath = receiptText
      ? 'receipt'
      : await waitForConnectionSaveReceipt(page);
    const committed = await committedCapabilities(browser, app.url, dir, {
      modelId: 'mock-fast',
      connectionName: 'Mock OAuth',
      shotName: 'e2e01-oauth-04-committed-tools',
    });
    result.serverCommitted = committed;
    result.checks.oauthToolsDeclaredOnServer = committed.saved;
    assert(committed.saved,
      `server snapshot does not declare tools for mock-fast: ${committed.rowText.slice(0, 500)}`);

    await goto(page, app.url, '/sessions');
    await waitForShell(page);
    await selectFirstSession(page);
    const label = await sessionModelLabel(page);
    result.modelLabel = label;
    result.checks.oauthSessionModel = label.includes('mock-fast');
    assert(result.checks.oauthSessionModel,
      `session did not bind the OAuth model: ${label}`);

    // Reset the shared mock's request log so this journey's request is
    // unambiguous; configuration (the marker reply) is preserved.
    await fetch(`${mock.url}/__mock/reset`, { method: 'POST' });
    await composer(page).fill(`OAuth 连接测试 ${marker}`);
    await page.locator('button[aria-label="发送消息"]').first().click();
    const replyResult = await waitForAssistant(page, {
      timeout: 90000, minChars: 10, quietMs: 2000,
    });
    result.reply = replyResult.text;
    result.checks.oauthReplyReceived = replyResult.text.includes(marker);
    assert(result.checks.oauthReplyReceived,
      `OAuth reply missing marker: ${replyResult.text.slice(0, 160)}`);
    const oauthBodyAfterSend = await page.locator('body').innerText();
    result.checks.oauthNoToolRefusalNotice =
      !/does not declare tool support|未声明工具支持/.test(oauthBodyAfterSend);
    assert(result.checks.oauthNoToolRefusalNotice,
      'the OAuth send still rendered a tool-requirement refusal');
    const after = await (await fetch(`${mock.url}/__mock/state`)).json();
    result.mockState = after;
    const sendEntries = (after.log || [])
      .filter((entry) => String(entry.user || '').includes(marker));
    result.oauthSendEntries = sendEntries.slice(-3);
    result.checks.oauthMockChat = after.chatCompletions >= 1 && sendEntries.length >= 1;
    result.checks.oauthRequestModel = sendEntries.some((entry) =>
      entry.model === 'mock-fast');
    result.checks.oauthRequestHasTools = sendEntries.some((entry) => entry.hasTools === true);
    assert(result.checks.oauthMockChat, 'the OAuth connection did not reach the mock model');
    assert(result.checks.oauthRequestModel,
      `the OAuth request did not name mock-fast: ${JSON.stringify(result.oauthSendEntries)}`);
    assert(result.checks.oauthRequestHasTools,
      'the OAuth chat request did not carry the declared tools');
    await shot(page, dir, 'e2e01-oauth-05-reply');

    result.ok = finalize();
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e01-oauth-result', result);
    assert(result.ok, 'one or more E2E 01 OAuth checks failed');
  } catch (error) {
    result.ok = false;
    result.failedChecks =
      (result.failedChecks && result.failedChecks.length)
        ? result.failedChecks
        : ['run-aborted'];
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
    result.oauthState = await (await fetch(`${oauth.url}/__mock/state`)).json().catch(() => null);
    result.evidence = writeJson(dir, 'e2e01-oauth-result', result);
    await shot(page, dir, 'e2e01-oauth-error').catch(() => {});
    throw error;
  } finally {
    await app.close();
  }
});
