// E2E 04: history edit and two branches.
//
// One three-turn session whose first user message carries a real PNG.  The
// operator edits that first message: new text, image removed, saved as a
// new version.  The original version must still render its image and the
// uploaded blob must still be readable.  From that point a branch is created
// and continues two more turns; switching back to the source session and the
// original version must keep message order and never mix in the branch's
// replies.  Both paths are exported as plain text: each export contains only
// its own selected path.  The branch title is renamed and its source banner
// must still point at the source session.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { test } from '@playwright/test';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert, assertEqual, assistantTexts, clientBundleInfo, composer,
  ensureIdle, goto, lastAssistantText, newSession, send, sessionModelLabel,
  shot, userTexts, waitForAssistant, waitForShell, waitForSync, writeJson,
} from './support/ui.mjs';
import { sourceTreeSha256, collectMockState } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

const MODEL_ID = 'mock-vision';
const MODEL_NAME = 'Mock Vision';

// A real 2x2 PNG (valid magic, IHDR, IDAT, IEND).  The server stores the
// bytes as a blob; the model adapter sends them as an image part, which the
// mock accepts as opaque base64.
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAF0lEQVR4nGP8z8DwnwEKmBgYAAA9+AED5N+9NQAAAABJRU5ErkJggg==';

function evidenceDir04() {
  const dir = path.join(REPO_ROOT, 'plans/evidence/e2e-04');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

async function downloadExport(page, buttonName, dir, filename) {
  const button = page.locator('.hc-export-controls')
    .getByRole('button', { name: buttonName, exact: true }).first();
  await button.waitFor({ state: 'visible', timeout: 15000 });
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
    button.click(),
  ]);
  const suggested = download.suggestedFilename() || filename;
  const target = path.join(dir, suggested);
  await download.saveAs(target);
  return { file: target, name: suggested, text: fs.readFileSync(target, 'utf8') };
}

async function messageRow(page, role, index = 0) {
  const rows = page.locator(`.hc-message-${role === 'user' ? 'out' : 'in'}:not(.hc-message-process)`);
  await rows.nth(index).waitFor({ state: 'visible', timeout: 15000 });
  return rows.nth(index);
}

async function firstUserRow(page) {
  return messageRow(page, 'user', 0);
}

async function waitForMessageCount(page, role, count, timeout = 30000) {
  const selector = role === 'user'
    ? '.hc-message-out:not(.hc-message-process)'
    : '.hc-message-in:not(.hc-message-process)';
  await page.waitForFunction(
    ([sel, n]) => document.querySelectorAll(sel).length >= n,
    [selector, count],
    { timeout });
}

async function selectSessionRow(page, title) {
  const row = page.locator('.wb-session-list .wb-list-button')
    .filter({ has: page.locator('strong', { hasText: new RegExp(`^${title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }) })
    .first();
  await row.waitFor({ state: 'visible', timeout: 15000 });
  await row.click();
  await composer(page).waitFor({ state: 'visible', timeout: 15000 });
  await waitForSync(page);
}

// Read-only transport probe exposed by the workbench sync bridge.  A failed
// command is either a stale adapter phase or a closed transport; the DOM
// cannot tell them apart.
async function syncProbe(page) {
  return page.evaluate(() =>
    (typeof window !== 'undefined' && window.__HC_SYNC__ ? window.__HC_SYNC__() : null));
}

// Diagnostic tap for the branch projection.  Sente travels over WebSocket
// `/chsk`; every server frame is kept so a state snapshot reference token
// can be resolved through `/sync-objects/<token>` when one appears.
function watchSyncObjects(page, sink) {
  const tokens = [];
  const closes = [];
  page.on('websocket', (ws) => {
    if (!ws.url().includes('/chsk')) return;
    closes.push({ at: new Date().toISOString(), event: 'opened', url: ws.url() });
    ws.on('close', () => closes.push({
      at: new Date().toISOString(), event: 'closed', url: ws.url(),
    }));
    ws.on('framereceived', (frame) => {
      const text = typeof frame.payload === 'string'
        ? frame.payload
        : frame.payload?.toString?.('utf8');
      if (typeof text !== 'string' || !text.includes('snapshot-ref')) return;
      sink.push(text.slice(0, 200000));
      for (const match of text.matchAll(/"token"\s*:\s*"([0-9a-f]{32})"/g)) {
        if (!tokens.includes(match[1])) tokens.push(match[1]);
      }
    });
  });
  return { tokens, closes };
}

// Resolve a sync-object token to the server's EDN snapshot and extract the
// branch session's own history row.  The token is a capability URL minted
// for this authenticated client; fetching it is a normal client operation.
async function fetchSyncObject(baseUrl, token) {
  const response = await fetch(`${baseUrl}/sync-objects/${token}`);
  return { status: response.status, text: await response.text() };
}

function snapshotObjectForBranch(text, branchSessionId) {
  return {
    bytes: text.length,
    messageByIdCount: (text.match(/:message\/by-id/g) || []).length,
    messageIdsCount: (text.match(/:message-ids/g) || []).length,
    sessionHistoryCount: (text.match(/:session\/history/g) || []).length,
    sessionRows: [...text.matchAll(/\[([a-zA-Z0-9_-]+)\s*\{[^{}]*?:message-ids/g)]
      .map((m) => m[1]).slice(0, 30),
  };
}

async function renameBranchSession(page, newTitle) {
  // The branch is the currently selected session right after branching;
  // its auto title is "Branch of <source>" and may vary, so rename through
  // the aria-current row instead of matching a title.
  const row = page.locator('.wb-session-list .wb-list-button[aria-current="true"]').first();
  await row.waitFor({ state: 'visible', timeout: 15000 });
  const item = row.locator('xpath=ancestor::li[1]');
  await item.getByRole('button', { name: /^重命名/ }).first().click();
  const input = item.locator('input[aria-label="新会话标题"]').first();
  await input.waitFor({ state: 'visible', timeout: 10000 });
  await input.fill(newTitle);
  await item.getByRole('button', { name: '保存', exact: true }).first().click();
  await page.locator('.wb-session-list .wb-list-button')
    .filter({ hasText: newTitle }).first()
    .waitFor({ state: 'visible', timeout: 30000 });
}

test('@core E2E 04 history edit and two branches', async ({ page, browser }) => {
  test.setTimeout(420000);
  const dir = evidenceDir04();
  const marker = `E2E04-${Date.now()}`;

  const mock = await startMockModel({
    replyScript: [
      { match: '第一轮', reply: `E2E04-REPLY-1 ${marker} 图片已收到。` },
      // Branch prompts contain 第二轮/第三轮 as substrings; the mock's
      // first-match-wins rule order must try the longer branch needles
      // before the plain round needles or every branch reply is hijacked.
      { match: '分支第二轮', reply: `E2E04-BRANCH-REPLY-2 ${marker} 分支第二轮完成。` },
      { match: '分支第三轮', reply: `E2E04-BRANCH-REPLY-3 ${marker} 分支第三轮完成。` },
      { match: '第二轮', reply: `E2E04-REPLY-2 ${marker} 第二轮完成。` },
      { match: '第三轮', reply: `E2E04-REPLY-3 ${marker} 第三轮完成。` },
      { reply: `E2E04-FALLBACK ${marker}` },
    ],
  });
  const app = await startApp({
    evidenceDir: dir,
    models: {
      connections: [{
        id: 'mock',
        name: 'Mock 视觉服务',
        baseUrl: `${mock.url}/v1`,
        models: [{
          id: MODEL_ID,
          displayName: MODEL_NAME,
          maxOutputTokens: 2048,
          capabilities: ['vision', 'tools'],
        }],
      }],
      roles: { main: { connection: 'mock', model: MODEL_ID } },
    },
  });

  const result = {
    test: 'E2E 04',
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
    browserEvents: [],
  };
  const syncFrames = [];
  const syncWatch = watchSyncObjects(page, syncFrames);
  const syncTokens = syncWatch.tokens;
  result.wsCloses = syncWatch.closes;
  page.on('pageerror', (error) =>
    result.browserEvents.push(`${new Date().toISOString()} pageerror ${String(error).slice(0, 300)}`));
  page.on('console', (message) => {
    if (message.type() === 'error') {
      result.browserEvents.push(`${new Date().toISOString()} console-error ${message.text().slice(0, 300)}`);
    }
  });

  const prompt1 = `E2E04 第一轮 ${marker}：请用一句话回答这张图片是什么。`;
  const prompt1Edited = `E2E04 第一轮已编辑 ${marker}：移除图片，只保留文字。`;
  const prompt2 = `E2E04 第二轮 ${marker}：请只回复第二。`;
  const prompt3 = `E2E04 第三轮 ${marker}：请只回复第三。`;
  const branchPrompt2 = `E2E04 分支第二轮 ${marker}：请只回复分支第二。`;
  const branchPrompt3 = `E2E04 分支第三轮 ${marker}：请只回复分支第三。`;
  const branchTitle = `E2E04 分支 ${marker}`;

  try {
    // --- Session setup: three turns, first message carries a real PNG -----
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    const modelLabel = await sessionModelLabel(page);
    result.modelLabel = modelLabel;
    result.checks.modelBound = modelLabel.includes(MODEL_NAME) || modelLabel.includes(MODEL_ID);
    assert(result.checks.modelBound, `session did not bind ${MODEL_NAME}: ${modelLabel}`);

    const fileInput = page.locator('.hc-conversation-with-attachments input[type="file"]').first();
    await fileInput.setInputFiles({
      name: 'e2e04-image.png',
      mimeType: 'image/png',
      buffer: Buffer.from(PNG_BASE64, 'base64'),
    });
    const uploadCard = page.locator('.hc-attachment-card').first();
    await uploadCard.waitFor({ state: 'visible', timeout: 15000 });
    await page.locator('.hc-attachment-card .hc-attachment-status-ok, .hc-attachment-card:has-text("可发送")')
      .first().waitFor({ state: 'visible', timeout: 30000 });

    await send(page, prompt1);
    const reply1 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.reply1 = reply1.text.slice(0, 200);
    result.checks.reply1 = reply1.text.includes('E2E04-REPLY-1');
    assert(result.checks.reply1, `first reply mismatch: ${reply1.text.slice(0, 120)}`);
    await ensureIdle(page, 20000);

    await send(page, prompt2);
    const reply2 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.reply2 = reply2.text.slice(0, 200);
    result.checks.reply2 = reply2.text.includes('E2E04-REPLY-2');
    assert(result.checks.reply2, `second reply mismatch: ${reply2.text.slice(0, 120)}`);
    await ensureIdle(page, 20000);

    await send(page, prompt3);
    const reply3 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.reply3 = reply3.text.slice(0, 200);
    result.checks.reply3 = reply3.text.includes('E2E04-REPLY-3');
    assert(result.checks.reply3, `third reply mismatch: ${reply3.text.slice(0, 120)}`);
    await ensureIdle(page, 20000);
    result.screenshots.push(await shot(page, dir, 'e2e04-01-three-turns'));

    // --- Edit the first message: new text, image removed -----------------
    const firstRow = await firstUserRow(page);
    const firstRowTextBefore = await firstRow.innerText();
    result.checks.firstRowHasImage = firstRowTextBefore.includes('e2e04-image.png');
    assert(result.checks.firstRowHasImage, `first message has no image chip: ${firstRowTextBefore.slice(0, 200)}`);
    const chipCountBefore = await firstRow.locator('.hc-attachment-stack .hc-chip').count();
    result.chipCountBefore = chipCountBefore;
    result.checks.imageChipVisible = chipCountBefore >= 1;
    assert(result.checks.imageChipVisible, 'the uploaded image chip is not rendered');

    await firstRow.getByRole('button', { name: '编辑', exact: true }).first().click();
    const editPanel = page.locator('.hc-inline-edit-panel[aria-label="编辑消息"]').first();
    await editPanel.waitFor({ state: 'visible', timeout: 15000 });
    const editTextarea = editPanel.locator('textarea[aria-label="编辑消息内容"]').first();
    await editTextarea.fill(prompt1Edited);
    const editAttachment = editPanel.locator('.hc-inline-edit-attachments .hc-chip').first();
    await editAttachment.waitFor({ state: 'visible', timeout: 10000 });
    await editPanel.getByRole('button', { name: /^移除附件/ }).first().click();
    await page.waitForTimeout(300);
    const editChipCount = await editPanel.locator('.hc-inline-edit-attachments .hc-chip').count();
    result.editChipCount = editChipCount;
    result.checks.editAttachmentRemoved = editChipCount === 0;
    assert(result.checks.editAttachmentRemoved, 'the image was not removed from the edit draft');
    await editPanel.getByRole('button', { name: '保存消息编辑' }).first().click();

    // The tracked command must reach :accepted; the editor closes on accept.
    await page.locator('.hc-inline-edit-panel[aria-label="编辑消息"]').first()
      .waitFor({ state: 'detached', timeout: 30000 }).catch(async () => {
        // The panel may stay mounted while hidden; fall back to the button.
        await page.getByRole('button', { name: /^编辑$/, exact: true }).first()
          .waitFor({ state: 'visible', timeout: 15000 });
      });

    // Version buttons appear once the message has more than one version.
    await firstRow.locator('.hc-message-versions').first()
      .waitFor({ state: 'visible', timeout: 30000 });
    const chipCountAfterEdit = await firstRow.locator('.hc-attachment-stack .hc-chip').count();
    result.chipCountAfterEdit = chipCountAfterEdit;
    result.checks.newVersionHasNoImage = chipCountAfterEdit === 0;
    const firstRowTextAfterEdit = await firstRow.innerText();
    result.checks.editedTextShown = firstRowTextAfterEdit.includes('已编辑')
      && firstRowTextAfterEdit.includes(prompt1Edited.slice(0, 30));
    assert(result.checks.newVersionHasNoImage,
      `edited version still shows ${chipCountAfterEdit} attachment chip(s)`);
    assert(result.checks.editedTextShown,
      `edited version does not show the new text: ${firstRowTextAfterEdit.slice(0, 300)}`);
    result.screenshots.push(await shot(page, dir, 'e2e04-02-edited-version'));

    // --- Original version still shows the image; blob bytes still exist ---
    const firstRowId = await firstRow.getAttribute('data-message-id');
    result.firstMessageId = firstRowId;
    await firstRow.locator('.hc-version-button', { hasText: 'v1' }).first().click();
    await page.waitForTimeout(500);
    const chipCountV1 = await firstRow.locator('.hc-attachment-stack .hc-chip').count();
    result.chipCountV1 = chipCountV1;
    result.checks.v1KeepsImage = chipCountV1 >= 1;
    assert(result.checks.v1KeepsImage, 'switching back to v1 lost the image chip');
    const v1Text = await firstRow.innerText();
    result.checks.v1KeepsOriginalText = v1Text.includes(prompt1.slice(0, 30));
    assert(result.checks.v1KeepsOriginalText, 'v1 does not show the original text');
    const imgSrc = await firstRow.locator('.hc-attachment-stack img').first()
      .getAttribute('src').catch(() => null);
    result.blobUrl = imgSrc;
    result.checks.blobUrlPresent = typeof imgSrc === 'string' && imgSrc.length > 0;
    assert(result.checks.blobUrlPresent, 'v1 image has no blob URL');
    if (imgSrc) {
      const blobPath = imgSrc.split('?')[0];
      const blobResponse = await fetch(`${app.url}${blobPath}`);
      const blobBytes = Buffer.from(await blobResponse.arrayBuffer());
      result.blobStatus = blobResponse.status;
      result.blobSha256 = crypto.createHash('sha256').update(blobBytes).digest('hex');
      result.blobSize = blobBytes.length;
      result.checks.blobReadable = blobResponse.ok && blobBytes.equals(Buffer.from(PNG_BASE64, 'base64'));
      assert(result.checks.blobReadable,
        `original image blob is not intact: status=${blobResponse.status} size=${blobBytes.length}`);
    }
    result.screenshots.push(await shot(page, dir, 'e2e04-03-original-version'));

    // Return to the edited version before branching.
    await firstRow.locator('.hc-version-button', { hasText: /^v2$/ }).first().click();
    await page.waitForTimeout(400);

    // --- Branch from the edited message ----------------------------------
    await firstRow.getByRole('button', { name: '从此处分支', exact: true }).first().click();
    const originBanner = page.locator('.wb-branch-origin');
    await originBanner.waitFor({ state: 'visible', timeout: 30000 });
    const bannerText = await originBanner.innerText();
    result.branchBannerText = bannerText;
    result.checks.branchBannerShowsSource = bannerText.includes('来源会话');
    assert(result.checks.branchBannerShowsSource, `branch banner missing source: ${bannerText}`);

    // The branch starts with the edited first message and its reply, plus
    // the second and third turns of the source path.
    await waitForSync(page, 60000);
    try {
      await waitForMessageCount(page, 'user', 3, 60000);
    } catch (waitError) {
      result.branchWaitError = waitError.message;
      result.branchPageText = (await page.locator('body').innerText()).slice(0, 4000);
      result.syncFramesCaptured = syncFrames.length;
      result.syncTokens = syncTokens.slice();
      const clientDb = await page.evaluate(() =>
        (typeof window !== 'undefined' && window.__HC_DB__ ? window.__HC_DB__() : null));
      result.clientDb = clientDb;
      // Probe the newest state-object snapshot the feed published.
      if (syncTokens.length) {
        const token = syncTokens[syncTokens.length - 1];
        const object = await fetchSyncObject(app.url, token);
        result.syncObjectStatus = object.status;
        result.syncObjectProbe = object.text ?
          snapshotObjectForBranch(object.text) : null;
        result.syncObjectBytes = object.text?.length ?? 0;
        fs.writeFileSync(path.join(dir, 'sync-object.edn'), object.text ?? '');
      }
      await shot(page, dir, 'e2e04-fail-branch-empty');
      throw waitError;
    }
    const branchUsers = await userTexts(page);
    const branchAssistants = await assistantTexts(page);
    result.branchUsers = branchUsers;
    result.branchAssistants = branchAssistants.map((t) => t.slice(0, 160));
    result.checks.branchHasEditedFirst = branchUsers.some((t) => t.includes('已编辑'));
    result.checks.branchHasPrompt2 = branchUsers.some((t) => t.includes('第二轮'));
    result.checks.branchHasPrompt3 = branchUsers.some((t) => t.includes('第三轮'));
    result.checks.branchHasReply1 = branchAssistants.some((t) => t.includes('E2E04-REPLY-1'));
    result.checks.branchHasReply3 = branchAssistants.some((t) => t.includes('E2E04-REPLY-3'));
    result.checks.branchNoBranchRepliesYet = !branchAssistants.some((t) => t.includes('BRANCH-REPLY'));
    assert(result.checks.branchHasEditedFirst, 'branch does not start from the edited version');
    assert(result.checks.branchHasReply3, 'branch is missing the source third reply');
    assert(result.checks.branchNoBranchRepliesYet, 'branch already shows branch-only replies');
    result.screenshots.push(await shot(page, dir, 'e2e04-04-branch-created'));

    // --- Continue two turns on the branch --------------------------------
    result.branchSyncBeforeSend = await syncProbe(page);
    result.branchEventsBeforeSend = await page.evaluate(() =>
      (typeof window !== 'undefined' && window.__HC_EVENTS__ ? window.__HC_EVENTS__() : null));
    await send(page, branchPrompt2);
    const branchReply2 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.branchReply2 = branchReply2.text.slice(0, 200);
    result.branchSyncAfterReply = await syncProbe(page);
    result.checks.branchReply2 = branchReply2.text.includes('E2E04-BRANCH-REPLY-2');
    assert(result.checks.branchReply2, `branch second reply mismatch: ${branchReply2.text.slice(0, 120)}`);
    await ensureIdle(page, 20000);

    await send(page, branchPrompt3);
    const branchReply3 = await waitForAssistant(page, { timeout: 90000, minChars: 10, quietMs: 2500 });
    result.branchReply3 = branchReply3.text.slice(0, 200);
    result.checks.branchReply3 = branchReply3.text.includes('E2E04-BRANCH-REPLY-3');
    assert(result.checks.branchReply3, `branch third reply mismatch: ${branchReply3.text.slice(0, 120)}`);
    await ensureIdle(page, 20000);
    result.screenshots.push(await shot(page, dir, 'e2e04-05-branch-two-turns'));

    // Branch order: edited user, reply1, user2, reply2, user3, reply3,
    // branch user, branch reply — user messages keep source order first.
    const branchUsersAfter = await userTexts(page);
    const branchAssistantsAfter = await assistantTexts(page);
    const userOrder = branchUsersAfter.map((t) =>
      t.includes('已编辑') ? 'edited' : t.includes('第二轮') && !t.includes('分支') ? 'src2'
        : t.includes('第三轮') && !t.includes('分支') ? 'src3'
        : t.includes('分支第二轮') ? 'br2' : t.includes('分支第三轮') ? 'br3' : 'other');
    result.branchUserOrder = userOrder;
    result.checks.branchUserOrder = JSON.stringify(userOrder) ===
      JSON.stringify(['edited', 'src2', 'src3', 'br2', 'br3']);
    assert(result.checks.branchUserOrder,
      `branch user order changed: ${JSON.stringify(userOrder)}`);
    const assistantOrderOk = branchAssistantsAfter.some((t) => t.includes('E2E04-REPLY-1'))
      && branchAssistantsAfter.some((t) => t.includes('E2E04-REPLY-3'))
      && branchAssistantsAfter.some((t) => t.includes('E2E04-BRANCH-REPLY-2'))
      && branchAssistantsAfter.some((t) => t.includes('E2E04-BRANCH-REPLY-3'));
    result.checks.branchRepliesPresent = assistantOrderOk;
    assert(result.checks.branchRepliesPresent, 'branch replies are incomplete');

    // --- Export the branch path ------------------------------------------
    const details = page.locator('details.hc-context-panel[aria-label="上下文与压缩"]').first();
    if (!(await details.getAttribute('open'))) await details.locator('summary').first().click();
    const branchExport = await downloadExport(page, '导出纯文本', dir, 'e2e04-branch.txt');
    result.branchExportFile = branchExport.file;
    result.checks.branchExportHasEdited = branchExport.text.includes('已编辑');
    result.checks.branchExportHasBranchTurns = branchExport.text.includes('分支第二轮')
      && branchExport.text.includes('分支第三轮');
    result.checks.branchExportHasBranchReplies = branchExport.text.includes('E2E04-BRANCH-REPLY-2')
      && branchExport.text.includes('E2E04-BRANCH-REPLY-3');
    result.checks.branchExportNoImageNote = !branchExport.text.includes('e2e04-image.png');
    result.checks.branchExportNoSourceOnlyText = !branchExport.text.includes(prompt1.slice(0, 30));
    assert(result.checks.branchExportHasEdited, 'branch export lacks the edited first message');
    assert(result.checks.branchExportHasBranchTurns, 'branch export lacks the branch turns');
    assert(result.checks.branchExportHasBranchReplies, 'branch export lacks branch replies');
    assert(result.checks.branchExportNoImageNote, 'branch export carries the removed image');
    assert(result.checks.branchExportNoSourceOnlyText,
      'branch export contains the pre-edit v1 text');

    // --- Rename the branch; source stays traceable ------------------------
    await renameBranchSession(page, branchTitle);
    result.checks.branchRenamed = (await page.locator('.wb-session-list .wb-list-button')
      .filter({ hasText: branchTitle }).count()) > 0;
    assert(result.checks.branchRenamed, `branch was not renamed to ${branchTitle}`);
    const bannerAfterRename = await originBanner.innerText().catch(() => '');
    result.branchBannerAfterRename = bannerAfterRename;
    result.checks.sourceTraceableAfterRename = bannerAfterRename.includes('来源会话');
    assert(result.checks.sourceTraceableAfterRename,
      `source banner lost after rename: ${bannerAfterRename}`);
    result.screenshots.push(await shot(page, dir, 'e2e04-06-branch-renamed'));

    // --- Back to the source session and the original version -------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await selectSessionRow(page, 'New session');
    // The branch page also has >=3 user turns, so the generic >=N wait can
    // pass before the session switch renders.  Wait for exactly the source
    // timeline's three user messages.
    await page.waitForFunction(
      () => document.querySelectorAll(
        '.hc-message-out:not(.hc-message-process)').length === 3,
      { timeout: 30000 });
    const srcUsers = await userTexts(page);
    const srcAssistants = await assistantTexts(page);
    result.srcUsers = srcUsers;
    result.srcAssistants = srcAssistants.map((t) => t.slice(0, 160));
    result.srcDbProbe = await page.evaluate(() => {
      if (typeof window === 'undefined' || !window.__HC_DB__) return null;
      const db = window.__HC_DB__();
      return {
        selected: db.selectedSessionId,
        sessionIds: db.sessionIds,
        sessionMessageIds: db.sessionMessageIds,
        messageSessions: db.messageSessions,
      };
    }).catch((e) => ({ probeError: String(e).slice(0, 300) }));
    result.checks.srcOrderUnchanged = JSON.stringify(srcUsers.map((t) =>
      t.includes('已编辑') ? 'edited' : t.includes('第二轮') ? 'src2' : t.includes('第三轮') ? 'src3' : 'other'))
      === JSON.stringify(['edited', 'src2', 'src3']);
    assert(result.checks.srcOrderUnchanged,
      `source user order changed: ${JSON.stringify(srcUsers)}`);
    result.checks.srcNoBranchReplies = !srcAssistants.some((t) => t.includes('BRANCH-REPLY'))
      && !srcUsers.some((t) => t.includes('分支第二轮') || t.includes('分支第三轮'));
    assert(result.checks.srcNoBranchReplies,
      'source session shows branch turns or replies');

    const srcFirstRow = await firstUserRow(page);
    await srcFirstRow.locator('.hc-version-button', { hasText: /^v1$/ }).first().click();
    await page.waitForTimeout(500);
    const srcV1Chips = await srcFirstRow.locator('.hc-attachment-stack .hc-chip').count();
    result.checks.srcV1ImageKept = srcV1Chips >= 1;
    assert(result.checks.srcV1ImageKept, 'source v1 lost its image after branch work');
    await srcFirstRow.locator('.hc-version-button', { hasText: /^v2$/ }).first().click();
    await page.waitForTimeout(400);
    result.screenshots.push(await shot(page, dir, 'e2e04-07-source-back'));

    // --- Export the source path -------------------------------------------
    const srcDetails = page.locator('details.hc-context-panel[aria-label="上下文与压缩"]').first();
    if (!(await srcDetails.getAttribute('open'))) await srcDetails.locator('summary').first().click();
    const sourceExport = await downloadExport(page, '导出纯文本', dir, 'e2e04-source.txt');
    result.sourceExportFile = sourceExport.file;
    result.checks.sourceExportHasEdited = sourceExport.text.includes('已编辑');
    result.checks.sourceExportHasThreeTurns = sourceExport.text.includes('第二轮')
      && sourceExport.text.includes('第三轮');
    result.checks.sourceExportHasSourceReplies = sourceExport.text.includes('E2E04-REPLY-1')
      && sourceExport.text.includes('E2E04-REPLY-3');
    result.checks.sourceExportNoBranchTurns = !sourceExport.text.includes('分支第二轮')
      && !sourceExport.text.includes('分支第三轮');
    result.checks.sourceExportNoBranchReplies = !sourceExport.text.includes('E2E04-BRANCH-REPLY');
    result.checks.sourceExportNoImage = !sourceExport.text.includes('e2e04-image.png');
    result.checks.sourceExportNoV1Text = !sourceExport.text.includes(prompt1.slice(0, 30));
    assert(result.checks.sourceExportHasEdited, 'source export lacks the edited first message');
    assert(result.checks.sourceExportHasThreeTurns, 'source export lacks later turns');
    assert(result.checks.sourceExportHasSourceReplies, 'source export lacks source replies');
    assert(result.checks.sourceExportNoBranchTurns, 'source export contains branch turns');
    assert(result.checks.sourceExportNoBranchReplies, 'source export contains branch replies');
    assert(result.checks.sourceExportNoImage, 'source export carries the removed image');
    assert(result.checks.sourceExportNoV1Text, 'source export contains the pre-edit v1 text');
    result.screenshots.push(await shot(page, dir, 'e2e04-08-source-exported'));

    result.mockState = await collectMockState({ model: mock });
    result.checks.mockRequests = (result.mockState.model?.chatCompletions || 0) >= 5;
    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e04-result', result);
    assert(result.ok, `one or more E2E 04 checks failed: ${JSON.stringify(result.checks)}`);
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.failSync = await syncProbe(page).catch((probeError) => ({
      probeError: String(probeError).slice(0, 300),
    }));
    result.failUsers = await userTexts(page).catch(() => null);
    result.failAssistants = await assistantTexts(page).catch(() => null);
    result.failConnectionBadge = await page
      .locator('.wb-badge[aria-label^="连接状态"]').first()
      .getAttribute('aria-label').catch(() => null);
    result.failEvents = await page.evaluate(() =>
      (typeof window !== 'undefined' && window.__HC_EVENTS__ ? window.__HC_EVENTS__() : null)).catch(() => null);
    result.mockState = await collectMockState({ model: mock }).catch(() => null);
    result.evidence = writeJson(dir, 'e2e04-result', result);
    throw error;
  } finally {
    await app.close();
    await mock.close();
  }
});
