// E2E 03: streaming reading and the next draft.
// A long SSE reply streams while the operator selects text, scrolls up, and
// types the next draft.  New chunks must not steal scroll or selection, must
// not overwrite the draft, and stopping must keep the received text without
// auto-sending the draft or appending late chunks.
import { test } from '@playwright/test';
import { startApp } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert, assertEqual, assistantTexts, clientBundleInfo, composer, ensureIdle, evidenceDir,
  goto, lastAssistantText, newSession, sessionModelLabel, shot, stopButton,
  turnLive, userTexts, waitForShell, waitForSync, writeJson,
} from './support/ui.mjs';
import { sourceTreeSha256 } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

test('@core E2E 03 streaming reading and next draft', async ({ page, browser }) => {
  const dir = evidenceDir('e2e03');
  const mock = await startMockModel({ chunkSize: 18, chunkDelayMs: 100 });
  const app = await startApp({
    evidenceDir: dir,
    models: {
      connections: [{
        id: 'mock',
        name: 'Mock 长流服务',
        baseUrl: `${mock.url}/v1`,
        models: [{ id: 'mock-long', displayName: 'Mock Long', maxOutputTokens: 8192, capabilities: ['tools'] }],
      }],
      roles: { main: { connection: 'mock', model: 'mock-long' } },
    },
  });
  const marker = `E2E03-${Date.now()}`;
  const prompt = `请输出长回复。标记 ${marker}`;
  const draft = `下一条草稿 ${marker}`;
  const result = {
    test: 'E2E 03',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    mockUrl: mock.url,
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    checks: {},
    screenshots: [],
  };
  try {
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    const modelLabel = await sessionModelLabel(page);
    result.modelLabel = modelLabel;
    result.checks.modelBound = modelLabel.includes('Mock Long') || modelLabel.includes('mock-long');
    assert(result.checks.modelBound, `new session did not bind mock-long: ${modelLabel}`);

    await composer(page).fill(prompt);
    await page.locator('button[aria-label="发送消息"]').first().click();

    const streamStart = Date.now();
    let streamed = 0;
    while (Date.now() - streamStart < 90000) {
      await page.waitForTimeout(250);
      streamed = (await lastAssistantText(page)).length;
      if (streamed >= 160) break;
    }
    result.streamedBeforeWait = streamed;
    result.checks.streamStarted = streamed >= 160;
    assert(result.checks.streamStarted, `stream did not reach 160 chars: ${streamed}`);

    await composer(page).fill(draft);
    await page.waitForTimeout(300);
    assertEqual(await composer(page).inputValue(), draft, 'draft was not typed');

    const selectionBefore = await page.evaluate(() => {
      const bodies = [...document.querySelectorAll('.hc-message-in:not(.hc-message-process) .hc-message-body')];
      const body = bodies[bodies.length - 1];
      if (!body) return '';
      const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
      const node = walker.nextNode();
      if (!node) return '';
      const range = document.createRange();
      range.setStart(node, 0);
      range.setEnd(node, Math.min(30, node.textContent.length));
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      return selection.toString();
    });
    result.selectionBefore = selectionBefore;
    assert(selectionBefore.length > 0, 'could not select streamed text');

    const scrollBefore = await page.evaluate(() => {
      const box = document.querySelector('.hc-conversation-messages');
      if (!box) return null;
      box.scrollTop = Math.max(0, box.scrollTop - 500);
      return box.scrollTop;
    });
    result.scrollBefore = scrollBefore;
    assert(scrollBefore !== null, 'conversation scroll container is missing');
    const lengthAtScroll = (await lastAssistantText(page)).length;

    await page.waitForTimeout(5000);
    const selectionAfter = await page.evaluate(() => window.getSelection().toString());
    const scrollAfter = await page.evaluate(() => {
      const box = document.querySelector('.hc-conversation-messages');
      return box ? box.scrollTop : null;
    });
    const draftAfterWait = await composer(page).inputValue();
    const lengthAfterWait = (await lastAssistantText(page)).length;
    result.selectionAfter = selectionAfter;
    result.scrollAfter = scrollAfter;
    result.draftAfterWait = draftAfterWait;
    result.lengthAtScroll = lengthAtScroll;
    result.lengthAfterWait = lengthAfterWait;
    result.checks.selectionKept = selectionAfter === selectionBefore;
    result.checks.scrollKept = scrollAfter !== null && Math.abs(scrollAfter - scrollBefore) <= 2;
    result.checks.draftKept = draftAfterWait === draft;
    result.checks.streamContinuedWhileReading = lengthAfterWait > lengthAtScroll;
    assert(result.checks.selectionKept, 'streaming changed the text selection');
    assert(result.checks.scrollKept, `streaming moved the scroll: ${scrollBefore} -> ${scrollAfter}`);
    assert(result.checks.draftKept, 'streaming overwrote the next draft');
    assert(result.checks.streamContinuedWhileReading,
      `stream stopped receiving while reading: ${lengthAtScroll} -> ${lengthAfterWait}`);
    result.screenshots.push(await shot(page, dir, 'e2e03-01-streaming-selection'));

    const stop = stopButton(page);
    result.checks.stopVisible = (await stop.count()) > 0;
    assert(result.checks.stopVisible, 'stop button is not visible during streaming');
    await stop.click();
    const stopStarted = Date.now();
    while (Date.now() - stopStarted < 15000) {
      if (!(await turnLive(page))) break;
      await page.waitForTimeout(200);
    }
    await page.waitForTimeout(1000);
    const stoppedLive = (await turnLive(page)) === false;
    const textAtStop = await lastAssistantText(page);
    const messagesAtStop = await page.locator('.hc-message').count();
    const usersAtStop = (await userTexts(page)).length;
    const mockAtStop = await (await fetch(`${mock.url}/__mock/state`)).json();
    await page.waitForTimeout(8000);

    const textAfterStop = await lastAssistantText(page);
    const messagesAfterStop = await page.locator('.hc-message').count();
    const usersAfterStop = (await userTexts(page)).length;
    const mockAfterStop = await (await fetch(`${mock.url}/__mock/state`)).json();
    result.charsAtStop = textAtStop.length;
    result.charsAfterStop = textAfterStop.length;
    result.requestsAtStop = mockAtStop.chatCompletions;
    result.requestsAfterStop = mockAfterStop.chatCompletions;
    result.messagesAtStop = messagesAtStop;
    result.messagesAfterStop = messagesAfterStop;
    result.usersAtStop = usersAtStop;
    result.usersAfterStop = usersAfterStop;
    result.draftAfterStop = await composer(page).inputValue();
    result.checks.contentRetained = textAtStop.trim().length > 0 && textAfterStop.trim().length > 0;
    result.checks.noLateAppend = textAfterStop.length === textAtStop.length;
    result.checks.noNewMessage = messagesAfterStop === messagesAtStop;
    result.checks.noAutoSend = usersAfterStop === usersAtStop && usersAfterStop === 1;
    result.checks.noNewModelRequest = mockAfterStop.chatCompletions === mockAtStop.chatCompletions;
    result.checks.stoppedLive = stoppedLive;
    result.checks.draftKeptAfterStop = result.draftAfterStop === draft;
    assert(result.checks.contentRetained, 'stop discarded the generated text');
    assert(result.checks.noLateAppend, `late text was appended: ${textAtStop.length} -> ${textAfterStop.length}`);
    assert(result.checks.noNewMessage, 'stop added a new message');
    assert(result.checks.noAutoSend, 'stop auto-sent the next draft');
    assert(result.checks.noNewModelRequest, 'a new model request started after stop');
    assert(result.checks.stoppedLive, 'the turn still reports live after stop');
    assert(result.checks.draftKeptAfterStop, 'the draft was lost after stop');
    result.screenshots.push(await shot(page, dir, 'e2e03-02-stopped'));

    await newSession(page);
    await page.waitForTimeout(800);
    const originalRow = page.locator('.wb-session-list .wb-list-button').nth(1);
    await originalRow.waitFor({ state: 'visible', timeout: 15000 });
    await originalRow.click();
    await composer(page).waitFor({ state: 'visible', timeout: 15000 });
    await waitForSync(page);
    await page.waitForFunction((expected) =>
      [...document.querySelectorAll('textarea[aria-label="消息输入"]')]
        .some((node) => node.value === expected), draft, { timeout: 15000 });
    const afterSwitchText = await page.locator('body').innerText();
    const draftAfterSwitch = draft;
    result.draftAfterSwitch = draftAfterSwitch;
    result.checks.switchNoFakeSpinner = (await turnLive(page)) === false
      && !afterSwitchText.includes('当前轮仍在生成');
    result.checks.switchKeepsReply = (await assistantTexts(page))
      .some((text) => text.includes(textAtStop.trim().slice(0, 40)));
    result.checks.switchKeepsDraft = draftAfterSwitch === draft;
    assert(result.checks.switchNoFakeSpinner, 'switching back shows a fake live turn');
    assert(result.checks.switchKeepsReply, 'switching back lost the stopped reply');
    assert(result.checks.switchKeepsDraft, 'switching back lost the next draft');
    result.screenshots.push(await shot(page, dir, 'e2e03-03-switched-back'));

    await page.locator('button[aria-label="发送消息"]').first().click();
    await page.waitForTimeout(1500);
    const usersAfterSend = await userTexts(page);
    result.checks.draftSentAfterSwitch = usersAfterSend.length === 2 && usersAfterSend[1].includes(marker);
    assert(result.checks.draftSentAfterSwitch, 'the draft could not be sent after switching back');
    await ensureIdle(page, 20000);
    result.screenshots.push(await shot(page, dir, 'e2e03-04-draft-sent'));

    result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json();
    result.checks.longStreamMockUsed = result.mockState.chatCompletions >= 2;
    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e03-result', result);
    assert(result.ok, 'one or more E2E 03 checks failed');
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockState = await (await fetch(`${mock.url}/__mock/state`)).json().catch(() => null);
    result.evidence = writeJson(dir, 'e2e03-result', result);
    throw error;
  } finally {
    await app.close();
    await mock.close();
  }
});
