// E2E 01: empty install to the first savable reply.
//
// This test intentionally exercises the new-connection form.  The typed id
// `manual-only` is deliberately absent from the mock's model listing, which
// reproduces D-12: a manually added model cannot be confirmed by a listing.
// The fix marks it `:not-returned` and lets `:unknown` send, showing
// `尚未确认` instead of disabling the composer.
import { test } from '@playwright/test';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert, assertEqual, composer, evidenceDir, goto, newSession,
  sessionModelLabel, settingsAddLocalConnection, settingsEnableModelAndBindMain,
  shot, userTexts, waitForAssistant, waitForShell, waitForSync,
  writeJson, assistantTexts, selectFirstSession, lastAssistantText,
} from './support/ui.mjs';

test.describe.configure({ mode: 'serial' });

test('@core E2E 01 empty install -> first savable reply', async ({ page, browser }) => {
  test.setTimeout(420000);
  const dir = evidenceDir('e2e01');
  const mock = await startMockModel();
  const app = await startApp({ evidenceDir: dir });
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
    checks: {},
    screenshots: [],
    browserEvents,
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
    await page.locator('.hc-settings').waitFor({ state: 'visible' });
    await page.locator('.hc-sync').filter({ hasText: '已同步' }).first()
      .waitFor({ state: 'visible', timeout: 30000 });
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

    const bind = await settingsEnableModelAndBindMain(page, {
      modelId: 'manual-only',
      connectionName: 'Mock 本地服务',
      declareTools: true,
    });
    result.bind = bind;
    result.screenshots.push(await shot(page, dir, 'e2e01-05-model-bound'));
    if (!bind.toolsDeclared) {
      result.errorStep = 'tools-not-declared';
      result.ok = false;
      result.finishedAt = new Date().toISOString();
      result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
      result.evidence = writeJson(dir, 'e2e01-result', result);
      throw new Error('manual model tool capability was not declared in the UI');
    }

    await goto(page, app.url, '/sessions');
    await waitForShell(page);
    await selectFirstSession(page);
    assertEqual(await composer(page).inputValue(), draft, 'draft was lost after visiting settings');
    result.checks.draftSurvivedSettings = true;
    const modelLabel = await sessionModelLabel(page);
    result.modelLabel = modelLabel;
    result.checks.sessionModelVisible = modelLabel.includes('manual-only');
    result.screenshots.push(await shot(page, dir, 'e2e01-06-draft-restored'));
    if (!result.checks.sessionModelVisible || bind.switchStatus === 'disabled') {
      result.knownDefect = 'D-12';
      result.blockedNotice = `手动模型未能绑定到当前会话：用于下一轮=${bind.switchStatus}；模型标签=${modelLabel}`;
      result.ok = false;
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
    if (blockedNotice) {
      result.checks.sendBlockedByD12 = true;
      result.knownDefect = 'D-12';
      result.blockedNotice = blockedNotice;
      result.ok = false;
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
    if (!userCreated) {
      const notices = await page.locator('.hc-message--warning, .hc-message--error, [role="alert"]')
        .allInnerTexts().catch(() => []);
      result.sendNotices = notices;
      result.bodyAfterSend = (await page.locator('body').innerText()).slice(0, 3000);
      result.errorStep = 'send-no-user-message';
      result.ok = false;
      result.finishedAt = new Date().toISOString();
      result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
      result.evidence = writeJson(dir, 'e2e01-result', result);
      await shot(page, dir, 'e2e01-07-send-no-user-message');
      throw new Error(`send did not create a user message; notices=${notices.join(' | ')}`);
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
    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e01-result', result);
    assert(result.ok, 'one or more E2E 01 checks failed');
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
    result.evidence = writeJson(dir, 'e2e01-result', result);
    throw error;
  } finally {
    await app.close();
    await mock.close();
  }
});
