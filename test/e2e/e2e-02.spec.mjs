// E2E 02: same-named model on two services.
//
// Two connections (a personal service and a work gateway) each enable a
// model whose display name is identical.  The settings selector must keep
// the two rows in separate connection groups and label the source instead
// of merging them.  Session A keeps the role default (personal), session B
// is switched to the work connection's same-named model via "用于下一轮",
// and each session's replies must come from its own mock service.  After
// the personal connection is disabled, session A's history stays readable,
// the composer refuses to send with an explicit "选择其他模型" repair path,
// and no request is silently rerouted to the still-enabled work service.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { test } from '@playwright/test';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert, assertEqual, assistantTexts, clientBundleInfo, composer,
  ensureIdle, goto, lastAssistantText, newSession, send, sessionModelLabel,
  shot, userTexts, waitForAssistant, waitForSettings, waitForShell,
  waitForSync, writeJson,
} from './support/ui.mjs';
import { sourceTreeSha256, collectMockState } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

const MODEL_ID = 'shared-model';
const MODEL_NAME = 'Shared Model';

function evidenceDir02() {
  const dir = path.join(REPO_ROOT, 'plans/evidence/e2e-02');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

async function openModelsTab(page, appUrl) {
  await goto(page, appUrl, '/settings');
  await waitForSettings(page);
  await page.getByRole('button', { name: '模型与角色', exact: true }).first().click();
  await page.getByRole('heading', { name: '模型选择' }).first()
    .waitFor({ state: 'visible', timeout: 30000 });
}

async function sessionRow(page, index) {
  const row = page.locator('.wb-session-list .wb-list-button').nth(index);
  await row.waitFor({ state: 'visible', timeout: 15000 });
  return row;
}

async function selectSessionAt(page, index) {
  const row = await sessionRow(page, index);
  await row.click();
  await composer(page).waitFor({ state: 'visible', timeout: 15000 });
  await page.waitForTimeout(500);
}

async function waitForModelLabel(page, source, timeout = 30000) {
  await page.waitForFunction((pattern) => {
    const el = document.querySelector('button[aria-label="打开设置选择模型"]');
    return el && new RegExp(pattern).test(el.innerText);
  }, source, { timeout });
  return sessionModelLabel(page);
}

async function mockCount(mock) {
  const state = await (await fetch(`${mock.url}/__mock/state`)).json();
  return state.chatCompletions;
}

test('@core E2E 02 same-named model on personal service and work gateway', async ({ page, browser }) => {
  test.setTimeout(420000);
  const dir = evidenceDir02();
  const marker = `E2E02-${Date.now()}`;
  const personalReply = `PERSONAL-REPLY ${marker}`;
  const workReply = `WORK-REPLY ${marker}`;

  const personal = await startMockModel({ reply: personalReply });
  const work = await startMockModel({ reply: workReply });
  const app = await startApp({
    evidenceDir: dir,
    models: {
      connections: [
        {
          id: 'personal',
          name: '个人服务',
          baseUrl: `${personal.url}/v1`,
          models: [{
            id: MODEL_ID,
            displayName: MODEL_NAME,
            maxOutputTokens: 2048,
            capabilities: ['tools'],
          }],
        },
        {
          id: 'work',
          name: '工作网关',
          baseUrl: `${work.url}/v1`,
          models: [{
            id: MODEL_ID,
            displayName: MODEL_NAME,
            maxOutputTokens: 2048,
            capabilities: ['tools'],
          }],
        },
      ],
      roles: { main: { connection: 'personal', model: MODEL_ID } },
    },
  });

  const result = {
    test: 'E2E 02',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    mockUrls: { personal: personal.url, work: work.url },
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    checks: {},
    screenshots: [],
    browserEvents: [],
  };
  const logBrowserEvent = (type, detail = '') => {
    result.browserEvents.push(`${new Date().toISOString()} ${type} ${detail}`.trim());
  };
  page.on('pageerror', (error) => logBrowserEvent('pageerror', String(error).slice(0, 300)));

  const countsBefore = { personal: 0, work: 0 };
  try {
    countsBefore.personal = await mockCount(personal);
    countsBefore.work = await mockCount(work);

    // --- Selector grouping: two connections, same model name, no merge ----
    await openModelsTab(page, app.url);
    const groups = page.locator('.hc-model-group');
    await groups.first().waitFor({ state: 'visible', timeout: 30000 });
    const groupCount = await groups.count();
    result.groupCount = groupCount;
    result.checks.twoConnectionGroups = groupCount >= 2;

    const personalGroup = groups.filter({ hasText: '个人服务' }).first();
    const workGroup = groups.filter({ hasText: '工作网关' }).first();
    result.checks.personalGroupVisible = (await personalGroup.count()) > 0;
    result.checks.workGroupVisible = (await workGroup.count()) > 0;

    const sameNameBadgeCount = await page.locator('.hc-model-row')
      .filter({ hasText: '同名模型，按连接区分' }).count();
    result.sameNameBadgeCount = sameNameBadgeCount;
    result.checks.sameNameNotMerged = sameNameBadgeCount >= 1;

    const personalRow = personalGroup.locator('.hc-model-row')
      .filter({ hasText: MODEL_ID }).first();
    const workRow = workGroup.locator('.hc-model-row')
      .filter({ hasText: MODEL_ID }).first();
    result.checks.personalRowExists = (await personalRow.count()) > 0;
    result.checks.workRowExists = (await workRow.count()) > 0;
    const personalRowText = await personalRow.innerText().catch(() => '');
    const workRowText = await workRow.innerText().catch(() => '');
    result.personalRowText = personalRowText.slice(0, 400);
    result.workRowText = workRowText.slice(0, 400);
    result.checks.personalSourceLabel = personalRowText.includes('来源：个人服务');
    result.checks.workSourceLabel = workRowText.includes('来源：工作网关');
    result.checks.sourcesDistinguishable = result.checks.personalSourceLabel
      && result.checks.workSourceLabel
      && personalRowText !== workRowText;
    assert(result.checks.twoConnectionGroups, `expected >=2 model groups, got ${groupCount}`);
    assert(result.checks.sameNameNotMerged, 'same-named models were merged or lack the split badge');
    assert(result.checks.sourcesDistinguishable,
      `rows do not name distinct sources:\nP:${personalRowText}\nW:${workRowText}`);
    result.screenshots.push(await shot(page, dir, 'e2e02-01-model-groups'));

    // --- Session A: role default resolves to the personal connection ------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    let labelA = await waitForModelLabel(page, MODEL_NAME);
    result.sessionAModelLabel = labelA;
    result.checks.sessionADefaultLabel = labelA.includes(MODEL_NAME)
      || labelA.includes(MODEL_ID);

    await openModelsTab(page, app.url);
    const currentModelLine = page.locator('.hc-muted')
      .filter({ hasText: '当前会话模型：' }).first();
    await currentModelLine.waitFor({ state: 'visible', timeout: 15000 });
    const currentModelTextA = await currentModelLine.innerText();
    result.currentModelTextA = currentModelTextA;
    // The settings line only renders display-name + host; the same display
    // name on both connections cannot be told apart here.  The personal
    // binding is proven by the first reply below (PERSONAL-REPLY).
    result.checks.sessionAModelRecorded = currentModelTextA.includes(MODEL_NAME);
    result.screenshots.push(await shot(page, dir, 'e2e02-02-session-a-default'));

    await goto(page, app.url, '/sessions');
    await composer(page).waitFor({ state: 'visible', timeout: 15000 });
    await send(page, `会话A第一条 ${marker}`);
    const replyA1 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.replyA1 = replyA1.text.slice(0, 300);
    result.checks.sessionAReplyFromPersonal = replyA1.text.includes('PERSONAL-REPLY');
    result.checks.sessionAReplyNotWork = !replyA1.text.includes('WORK-REPLY');
    assert(result.checks.sessionAReplyFromPersonal, `session A did not use personal mock: ${replyA1.text.slice(0, 120)}`);

    // --- Session B: switch the same-named model to the work connection ----
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await waitForModelLabel(page, MODEL_NAME);

    await openModelsTab(page, app.url);
    const workRowForSwitch = workGroup.locator('.hc-model-row')
      .filter({ hasText: MODEL_ID }).first();
    const switchButton = workRowForSwitch.getByRole('button', { name: '用于下一轮', exact: true }).first();
    result.checks.workSwitchVisible = (await switchButton.count()) > 0;
    result.checks.workSwitchEnabled = result.checks.workSwitchVisible
      && !(await switchButton.isDisabled());
    assert(result.checks.workSwitchEnabled, 'work row "用于下一轮" is missing or disabled for session B');
    await switchButton.click();
    await page.getByText(/已提交模型切换|模型切换已接受/).first()
      .waitFor({ state: 'visible', timeout: 30000 });
    const currentModelTextB = await currentModelLine.innerText();
    result.currentModelTextB = currentModelTextB;
    result.checks.sessionBModelRecorded = currentModelTextB.includes(MODEL_NAME);
    result.screenshots.push(await shot(page, dir, 'e2e02-03-session-b-switched'));

    await goto(page, app.url, '/sessions');
    await composer(page).waitFor({ state: 'visible', timeout: 15000 });
    await send(page, `会话B第一条 ${marker}`);
    const replyB1 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.replyB1 = replyB1.text.slice(0, 300);
    result.checks.sessionBReplyFromWork = replyB1.text.includes('WORK-REPLY');
    result.checks.sessionBReplyNotPersonal = !replyB1.text.includes('PERSONAL-REPLY');
    assert(result.checks.sessionBReplyFromWork, `session B did not use work mock: ${replyB1.text.slice(0, 120)}`);

    // --- Session A keeps its original selection after B switched ---------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    // Order is newest-first: B was just updated, A is second.
    await selectSessionAt(page, 1);
    labelA = await sessionModelLabel(page);
    result.sessionARelabel = labelA;
    await send(page, `会话A第二条 ${marker}`);
    const replyA2 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.replyA2 = replyA2.text.slice(0, 300);
    result.checks.sessionAKeepsPersonal = replyA2.text.includes('PERSONAL-REPLY')
      && !replyA2.text.includes('WORK-REPLY');
    assert(result.checks.sessionAKeepsPersonal,
      `session A lost its personal binding after B switched: ${replyA2.text.slice(0, 120)}`);

    // --- New session takes the assistant default (personal) ---------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await page.waitForTimeout(800);
    const labelC = await sessionModelLabel(page);
    result.sessionCModelLabel = labelC;
    result.checks.sessionCDefaultLabel = labelC.includes(MODEL_NAME)
      || labelC.includes(MODEL_ID);
    await send(page, `会话C第一条 ${marker}`);
    const replyC1 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.replyC1 = replyC1.text.slice(0, 300);
    result.checks.sessionCDefaultPersonal = replyC1.text.includes('PERSONAL-REPLY');
    assert(result.checks.sessionCDefaultPersonal,
      `new session did not take the assistant default: ${replyC1.text.slice(0, 120)}`);

    const countsBeforeDisable = {
      personal: await mockCount(personal),
      work: await mockCount(work),
    };
    result.countsBeforeDisable = countsBeforeDisable;

    // The server refuses to disable a connection that a role still names
 // ("role names a disabled connection").  Move the main role to the work
    // connection first; session bindings are immutable snapshots and are
    // unaffected by the role change.
    await openModelsTab(page, app.url);
    const workRoleRow = workGroup.locator('.hc-model-row')
      .filter({ hasText: MODEL_ID }).first();
    await workRoleRow.getByRole('button', { name: '选为当前角色', exact: true }).first().click();
    const saveRoleButton = page.getByRole('button', { name: '保存角色绑定', exact: true }).first();
    await saveRoleButton.waitFor({ state: 'visible', timeout: 15000 });
    await saveRoleButton.click();
    await page.getByText('角色绑定已保存', { exact: false }).first()
      .waitFor({ state: 'visible', timeout: 30000 });
    result.checks.roleReboundToWork = true;

    // --- Disable the personal connection ----------------------------------
    await goto(page, app.url, '/settings');
    await page.getByRole('button', { name: '供应商与连接', exact: true }).first().click();
    const personalCard = page.locator('.hc-connection-card')
      .filter({ hasText: '个人服务' }).first();
    await personalCard.waitFor({ state: 'visible', timeout: 30000 });
    await personalCard.getByRole('button', { name: '编辑', exact: true }).first().click();
    const editor = page.locator('section.hc-panel')
      .filter({ hasText: '编辑连接：' }).first();
    await editor.waitFor({ state: 'visible', timeout: 30000 });
    await editor.getByRole('button', { name: '停用此连接', exact: true }).first().click();
    await page.waitForTimeout(400);
    const saveButton = editor.getByRole('button', { name: '保存连接', exact: true }).first();
    result.saveButtonDisabled = await saveButton.isDisabled().catch(() => null);
    await editor.getByRole('button', { name: '保存连接', exact: true }).first().click();
    const savedNotice = page.getByText(/连接已保存|保存失败|未接受|错误|仍需处理/).first();
    try {
      await savedNotice.waitFor({ state: 'visible', timeout: 30000 });
      result.saveOutcome = await savedNotice.innerText();
    } catch {
      result.saveOutcome = 'timeout';
      result.bodyAtSaveTimeout = (await page.locator('body').innerText()).slice(0, 4000);
      throw new Error('save connection did not produce a notice');
    }
    assert(String(result.saveOutcome).includes('连接已保存'),
      `connection save failed: ${result.saveOutcome}`);
    await personalCard.getByText('已停用', { exact: false }).first()
      .waitFor({ state: 'visible', timeout: 30000 });
    result.screenshots.push(await shot(page, dir, 'e2e02-04-personal-disabled'));

    // --- Disabled personal: history readable, send refused, no failover ---
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    // Newest-first order is C (newest), A (second message), B: A is index 1.
    await selectSessionAt(page, 1);

    // The header label must still name the original binding plus its state.
    await page.waitForFunction(() => {
      const el = document.querySelector('button[aria-label="打开设置选择模型"]');
      return el && /已停用/.test(el.innerText);
    }, null, { timeout: 30000 });
    labelA = await sessionModelLabel(page);
    result.sessionALabelAfterDisable = labelA;
    result.checks.labelShowsDisabled = labelA.includes('已停用');

    // History remains readable.
    const usersA = await userTexts(page);
    const assistantsA = await assistantTexts(page);
    result.checks.historyUserMessagesKept = usersA.some((t) => t.includes(marker));
    result.checks.historyRepliesKept = assistantsA.some((t) => t.includes('PERSONAL-REPLY'));
    assert(result.checks.historyUserMessagesKept, 'session A user history lost after disable');
    assert(result.checks.historyRepliesKept, 'session A reply history lost after disable');

    // Composer explains the unavailable model and offers a repair path.
    await composer(page).fill(`停用后草稿 ${marker}`);
    const sendButton = page.locator('button[aria-label="发送消息"]').first();
    await page.waitForTimeout(800);
    result.sendDisabledAfterDisable = await sendButton.isDisabled();
    const warningBanner = page.locator('.hc-banner.hc-banner-warning')
      .filter({ hasText: '所选连接已停用' }).first();
    result.checks.unavailableBannerVisible = (await warningBanner.count()) > 0;
    const bannerText = await warningBanner.innerText().catch(() => '');
    result.bannerText = bannerText.slice(0, 300);
    result.checks.bannerOffersOtherModel = bannerText.includes('选择其他模型');
    const otherModelButton = warningBanner.getByRole('button', { name: '选择其他模型' }).first();
    result.checks.otherModelButtonPresent = (await otherModelButton.count()) > 0;
    assert(result.checks.unavailableBannerVisible,
      'no disabled-connection warning banner on session A');
    assert(result.checks.bannerOffersOtherModel,
      `banner does not offer a model switch: ${bannerText}`);
    assert(result.sendDisabledAfterDisable,
      'send button stayed enabled while the bound connection is disabled');
    result.screenshots.push(await shot(page, dir, 'e2e02-05-send-refused'));

    // Clicking the repair path opens settings; the binding itself is not
    // silently changed.
    if (result.checks.otherModelButtonPresent) {
      await otherModelButton.click();
      await page.locator('.hc-settings').first()
        .waitFor({ state: 'visible', timeout: 30000 });
      result.checks.repairPathOpensSettings = true;
      await goto(page, app.url, '/sessions');
      await selectSessionAt(page, 1);
      labelA = await sessionModelLabel(page);
      result.sessionALabelAfterRepairVisit = labelA;
      result.checks.bindingNotSilentlyChanged = labelA.includes('已停用')
        && labelA.includes(MODEL_NAME);
    }

    // No silent failover: neither mock received a new request.
    await page.waitForTimeout(3000);
    const countsAfterBlocked = {
      personal: await mockCount(personal),
      work: await mockCount(work),
    };
    result.countsAfterBlocked = countsAfterBlocked;
    result.checks.noSilentPersonalRequest = countsAfterBlocked.personal === countsBeforeDisable.personal;
    result.checks.noSilentWorkFailover = countsAfterBlocked.work === countsBeforeDisable.work;
    assert(result.checks.noSilentWorkFailover,
      `work mock received ${countsAfterBlocked.work - countsBeforeDisable.work} unexpected request(s)`);

    // --- Control: session B on the work gateway still sends ---------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    // Order is still C, A, B; B is the last row.
    await selectSessionAt(page, 2);
    await waitForModelLabel(page, 'Shared Model');
    await send(page, `会话B停用对照 ${marker}`);
    const replyB2 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.replyB2 = replyB2.text.slice(0, 300);
    result.checks.workSessionStillSends = replyB2.text.includes('WORK-REPLY');
    assert(result.checks.workSessionStillSends,
      `work session lost its binding after personal disable: ${replyB2.text.slice(0, 120)}`);
    result.screenshots.push(await shot(page, dir, 'e2e02-06-work-control'));

    result.mockState = await collectMockState({ personal, work });
    result.checks.personalMockUsed = result.mockState.personal.chatCompletions
      >= countsBefore.personal + 2;
    result.checks.workMockUsed = result.mockState.work.chatCompletions
      >= countsBefore.work + 2;
    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e02-result', result);
    assert(result.ok, `one or more E2E 02 checks failed: ${JSON.stringify(result.checks)}`);
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockState = await collectMockState({ personal, work }).catch(() => null);
    result.evidence = writeJson(dir, 'e2e02-result', result);
    throw error;
  } finally {
    await app.close();
    await personal.close();
    await work.close();
  }
});
