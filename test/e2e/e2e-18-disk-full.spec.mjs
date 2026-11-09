// E2E 18: a real ENOSPC against a size-bounded tmpfs, honest save receipts,
// a browser localStorage quota failure, restart after freeing space, and the
// `--check` refusal to re-initialize a data directory whose store is gone.
//
// The suite must run through `npm run e2e:18` (or `node test/e2e/run-e2e.mjs`)
// so the whole Playwright process lives in `unshare -Ur -m`.  A direct
// `playwright test` has no mount capability; this spec records a
// `blockedReason` and skips, unless HC_E2E_REQUIRE_DISK=1 turns that into a
// hard failure.
import { test } from '@playwright/test';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { runAppCheck, startApp } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import { checksOk, recordEvidence } from './support/evidence.mjs';
import {
  assert, assertEqual, assistantTexts, composer, ensureIdle, evidenceDir, goto,
  newSession, shot, userTexts, waitForAssistant, waitForSync,
} from './support/ui.mjs';

const EVIDENCE_NAME = 'e2e18';
const MARKER = `E2E18-${Date.now()}`;
const FILL_NAME = 'fill.bin';
const DRAFT_TEXT = `长草稿 ${MARKER}\n${'字'.repeat(20000)}`;

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    timeout: 180000,
    maxBuffer: 16 * 1024 * 1024,
    ...options,
  });
  return {
    status: result.status,
    signal: result.signal ?? null,
    stdout: result.stdout || '',
    stderr: result.stderr || '',
    error: result.error ? String(result.error.message) : null,
  };
}

function dfOf(root) {
  const result = run('df', ['-P', '-k', root]);
  assertEqual(result.status, 0, `df failed: ${result.stderr}`);
  const line = result.stdout.trim().split('\n').at(-1) || '';
  const fields = line.split(/\s+/);
  return {
    raw: line,
    filesystem: fields[0] || null,
    totalKb: Number(fields[1] || 0),
    usedKb: Number(fields[2] || 0),
    availableKb: Number(fields[3] || 0),
    usePercent: Number(String(fields[4] || '').replace('%', '')),
  };
}

function mountProbe(directory) {
  const mounted = run('mount', ['-t', 'tmpfs', '-o', 'size=1m', 'tmpfs', directory]);
  if (mounted.status !== 0) {
    return { ok: false, reason: `tmpfs mount unavailable: ${(mounted.stderr || mounted.stdout).trim() || `exit ${mounted.status}`}` };
  }
  const unmounted = run('umount', [directory]);
  if (unmounted.status !== 0) run('umount', ['-l', directory]);
  return { ok: true };
}

// The E2E process itself must be able to mount.  Testing `unshare ... true`
// is not enough: a direct `playwright test` can create another namespace but
// still cannot mount in its own.
function diskPrerequisites() {
  if (process.platform !== 'linux') {
    return { ok: false, reason: `disk-full injection needs Linux; running on ${process.platform}` };
  }
  for (const binary of ['unshare', 'mount', 'umount', 'dd', 'df']) {
    const found = run('sh', ['-c', `command -v ${binary} >/dev/null 2>&1`]);
    if (found.status !== 0) return { ok: false, reason: `missing binary: ${binary}` };
  }
  const probe = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e18-probe-'));
  try {
    const mounted = mountProbe(probe);
    if (!mounted.ok) {
      const unshare = run('unshare', ['-Ur', '-m', '--', 'true']);
      const namespaceHint = process.env.HC_E2E_NAMESPACE === '1'
        ? 'the process is already in the E2E namespace but tmpfs mount was denied'
        : (unshare.status === 0
            ? 'unshare works but this process is not inside it; run npm run e2e:18'
            : 'user namespaces are unavailable on this host');
      return { ok: false, reason: `${mounted.reason} (${namespaceHint})` };
    }
    return { ok: true, reason: null };
  } finally {
    fs.rmSync(probe, { recursive: true, force: true });
  }
}

async function downloadText(download) {
  const stream = await download.createReadStream();
  if (stream) {
    const chunks = [];
    for await (const chunk of stream) chunks.push(Buffer.from(chunk));
    return Buffer.concat(chunks).toString('utf8');
  }
  const file = await download.path();
  return fs.readFileSync(file, 'utf8');
}

async function waitForDraftInStorage(page, needle, timeout = 20000) {
  await page.waitForFunction((text) => {
    const raw = window.localStorage.getItem('hc.workbench.v1') || '';
    return raw.includes(text);
  }, needle, { timeout });
}

async function fillLocalStorage(page) {
  return page.evaluate(() => {
    // Fill down to a single byte.  The restored rejected draft can leave a
    // few bytes of headroom, so stopping at 64-byte chunks would let the next
    // small edit save successfully and hide the intended quota failure.
    const chunkSizes = [256 * 1024, 64 * 1024, 16 * 1024, 4096, 1024, 256,
                        64, 32, 8, 1];
    const keys = [];
    let index = 0;
    let attempts = 0;
    for (const size of chunkSizes) {
      const value = 'x'.repeat(size);
      for (;;) {
        attempts += 1;
        if (attempts > 400000) {
          throw new Error('localStorage did not report QuotaExceededError');
        }
        const key = `hc-e2e18-fill-${index}`;
        try {
          window.localStorage.setItem(key, value);
          keys.push(key);
          index += 1;
        } catch (error) {
          const name = String(error && error.name);
          const message = String(error && error.message);
          if (!/QuotaExceeded/i.test(`${name} ${message}`)) throw error;
          break;
        }
      }
    }
    window.__hcE2E18FillKeys = keys;
    return { keys: keys.length };
  });
}

async function clearLocalStorage(page) {
  await page.evaluate(() => {
    for (const key of window.__hcE2E18FillKeys || []) {
      try { window.localStorage.removeItem(key); } catch { /* quota is already released */ }
    }
    window.__hcE2E18FillKeys = [];
  });
}

test('@core @disk E2E 18 disk full, honest receipts, restart and recovery check', async ({ page, browser }) => {
  test.setTimeout(900000);
  const dir = evidenceDir(EVIDENCE_NAME);
  const startedAt = new Date().toISOString();
  const requestedMiB = Number(process.env.HC_E2E18_TMPFS_MB);
  const tmpfsMiB = Number.isFinite(requestedMiB) && requestedMiB >= 16
    ? Math.floor(requestedMiB)
    : 128;
  const checks = {};
  const evidence = {
    test: 'E2E 18',
    mode: 'automated-real-enospc',
    os: `${process.platform} ${os.release()}`,
    namespace: process.env.HC_E2E_NAMESPACE === '1',
    tmpfsMiB,
    marker: MARKER,
    checks,
    df: { before: null, full: null, after: null },
    blockedReason: null,
    appUrl: null,
    mockUrl: null,
    chromeVersion: browser.version(),
    screenshots: [],
  };

  const prerequisite = diskPrerequisites();
  if (!prerequisite.ok) {
    evidence.blockedReason = prerequisite.reason;
    checks.platformSupported = false;
    const result = recordEvidence({
      dir,
      name: EVIDENCE_NAME,
      test: evidence.test,
      mode: evidence.mode,
      startedAt,
      extra: {
        os: evidence.os,
        namespace: evidence.namespace,
        tmpfsMiB: evidence.tmpfsMiB,
        marker: evidence.marker,
        blockedReason: evidence.blockedReason,
        df: evidence.df,
        chromeVersion: evidence.chromeVersion,
      },
      checks,
      ok: false,
    });
    evidence.evidence = result.evidence;
    if (process.env.HC_E2E_REQUIRE_DISK === '1') {
      throw new Error(`E2E 18 requires a real disk-full environment: ${prerequisite.reason}`);
    }
    test.skip(true, prerequisite.reason);
    return;
  }
  checks.platformSupported = true;

  let root = null;
  let mounted = false;
  let app = null;
  let mock = null;
  let fillPath = null;
  let result = null;

  try {
    root = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e18-'));
    fillPath = path.join(root, FILL_NAME);
    const mount = run('mount', [
      '-t', 'tmpfs', '-o', `size=${evidence.tmpfsMiB}m,mode=700`, 'tmpfs', root,
    ]);
    assertEqual(mount.status, 0, `tmpfs mount failed: ${mount.stderr}`);
    mounted = true;
    checks.tmpfsMounted = mount.status === 0;
    evidence.df.before = dfOf(root);
    checks.tmpfsCapacityMatches = Math.abs(evidence.df.before.totalKb
      - evidence.tmpfsMiB * 1024) <= 1024;
    assert(checks.tmpfsCapacityMatches,
      `mounted tmpfs capacity does not match ${evidence.tmpfsMiB} MiB: ${JSON.stringify(evidence.df.before)}`);

    const requestedPort = Number(process.env.HC_E2E18_PORT);
    const port = Number.isInteger(requestedPort) && requestedPort > 0
      ? requestedPort
      : 18018;
    mock = await startMockModel({ reply: `E2E18-MOCK ${MARKER}` });
    evidence.mockUrl = mock.url;
    const appOptions = {
      root,
      keepTemp: true,
      port,
      evidenceDir: dir,
      healthTimeoutMs: 180000,
      // The client recognizes the ENOSPC cause by English keywords.  Pin the
      // server's C locale so LMDB's strerror is "No space left on device"
      // regardless of the workstation language.
      env: { LC_ALL: 'C', LANG: 'C' },
      models: {
        connections: [{
          id: 'mock',
          name: 'Mock E2E18',
          baseUrl: `${mock.url}/v1`,
          // The Plan/Reviewer roles require the model to declare its program
          // tool; this matches the same fixture requirement observed in E2E 19.
          models: [{ id: 'mock-fast', displayName: 'Mock Fast', capabilities: ['tools'] }],
        }],
        roles: { main: { connection: 'mock', model: 'mock-fast' } },
      },
    };
    app = await startApp(appOptions);
    evidence.appUrl = app.url;
    assertEqual(app.port, port, 'startApp did not bind the fixed port');

    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);

    // One successful text attachment and one reply prove the pre-full state.
    const attachmentName = `note-${MARKER}.txt`;
    const attachmentPath = path.join(os.tmpdir(), attachmentName);
    fs.writeFileSync(attachmentPath, `E2E18 attachment ${MARKER}\n`);
    await page.locator('input[type="file"]').first().setInputFiles(attachmentPath);
    const readyCard = page.locator('.hc-attachment-card').filter({ hasText: attachmentName }).first();
    await readyCard.waitFor({ state: 'visible', timeout: 60000 });
    await page.waitForFunction((name) => {
      const cards = [...document.querySelectorAll('.hc-attachment-card')];
      const card = cards.find((node) => node.innerText.includes(name));
      return Boolean(card && /可发送/.test(card.innerText));
    }, attachmentName, { timeout: 60000 });
    const readyText = await readyCard.innerText();
    checks.attachmentReadyBeforeFull = /可发送/.test(readyText);
    assert(checks.attachmentReadyBeforeFull, `attachment did not become sendable: ${readyText}`);

    const firstMessage = `附件与首条消息 ${MARKER}`;
    await composer(page).fill(firstMessage);
    await page.locator('button[aria-label="发送消息"]').first().click();
    const firstReply = await waitForAssistant(page, { timeout: 120000, minChars: 5, quietMs: 1500 });
    checks.firstReplyArrived = firstReply.text.includes('E2E18-MOCK');
    assert(checks.firstReplyArrived, `first mock reply missing: ${firstReply.text.slice(0, 120)}`);
    await ensureIdle(page, 30000);

    // The 20,000-character draft must be on disk in localStorage before the
    // tmpfs fills; otherwise the restart check would test nothing.
    await composer(page).fill(DRAFT_TEXT);
    await waitForDraftInStorage(page, '字'.repeat(40));
    checks.longDraftPersisted = await page.evaluate(() =>
      (window.localStorage.getItem('hc.workbench.v1') || '').includes('字'.repeat(40)));
    assert(checks.longDraftPersisted, 'the 20,000-character draft was not persisted');
    evidence.screenshots.push(await shot(page, dir, 'e2e18-01-before-full'));

    const fill = run('dd', [
      'if=/dev/zero', `of=${fillPath}`, 'bs=1M',
      `count=${evidence.tmpfsMiB + 64}`, 'conv=fsync',
    ], { timeout: 300000, env: { ...process.env, LC_ALL: 'C' } });
    evidence.dd = { status: fill.status, stderr: fill.stderr.slice(-2000) };
    checks.ddReportsEnospc = /No space left on device/i.test(fill.stderr);
    assert(checks.ddReportsEnospc, `dd did not report ENOSPC: ${fill.stderr.slice(-800)}`);
    evidence.df.full = dfOf(root);
    checks.dfReportsFull = evidence.df.full.usePercent === 100;
    assert(checks.dfReportsFull, `tmpfs is not 100% full: ${JSON.stringify(evidence.df.full)}`);

    // Sending while the store cannot commit must be rejected with the
    // standard Chinese storage copy (or the explicit code), not a fake
    // "saved" receipt.
    await page.locator('button[aria-label="发送消息"]').first().click();
    const storageBanner = page.locator('.hc-banner-error')
      .filter({ hasText: /空间不足，暂时无法保存更多内容|COMMAND_STORE_FAILED/ })
      .first();
    await storageBanner.waitFor({ state: 'visible', timeout: 120000 });
    const bannerText = (await storageBanner.innerText()).trim();
    evidence.banner = bannerText;
    checks.sendRejected = /COMMAND_STORE_FAILED|空间不足/.test(bannerText);
    checks.storageBannerVisible = /空间不足，暂时无法保存更多内容/.test(bannerText)
      || /COMMAND_STORE_FAILED/.test(bannerText);
    assert(checks.sendRejected, `the send was not visibly rejected: ${bannerText}`);
    assert(checks.storageBannerVisible, `unexpected storage banner: ${bannerText}`);

    const usersNow = await userTexts(page);
    const assistantsNow = await assistantTexts(page);
    checks.oldUserMessageReadable = usersNow.some((text) => text.includes(firstMessage));
    checks.oldReplyReadable = assistantsNow.some((text) => text.includes('E2E18-MOCK'));
    assert(checks.oldUserMessageReadable, 'the earlier user message disappeared after ENOSPC');
    assert(checks.oldReplyReadable, 'the earlier assistant reply disappeared after ENOSPC');

    // A rejected send must restore the submitted draft.  Keep this as a
    // recorded check rather than an early abort so the remaining ENOSPC,
    // restart and --check facts are still collected when the SUT fails it.
    const draftRestored = await page.waitForFunction((length) =>
      [...document.querySelectorAll('textarea[aria-label="消息输入"]')]
        .some((node) => node.value.length === length),
    DRAFT_TEXT.length, { timeout: 5000 }).then(() => true).catch(() => false);
    const draftAfterReject = await composer(page).inputValue();
    evidence.draftLength = draftAfterReject.length;
    checks.draftKept = draftRestored && draftAfterReject === DRAFT_TEXT;

    const saveLabels = await page.locator('.hc-composer-save').evaluateAll((nodes) =>
      nodes.map((node) => node.innerText.trim()).filter(Boolean));
    evidence.saveLabels = saveLabels;
    checks.noFakeSavedReceipt = !saveLabels.some((label) => /已保存/.test(label))
      && !/草稿已保存/.test(await page.locator('body').innerText());
    assert(checks.noFakeSavedReceipt, `a save receipt claimed success: ${JSON.stringify(saveLabels)}`);

    // A new upload cannot become sendable while the store is full.
    const failedName = `full-${MARKER}.txt`;
    const failedPath = path.join(os.tmpdir(), failedName);
    fs.writeFileSync(failedPath, `E2E18 overflow ${MARKER}\n`);
    await page.locator('input[type="file"]').first().setInputFiles(failedPath);
    const failedCard = page.locator('.hc-attachment-card').filter({ hasText: failedName }).first();
    await failedCard.waitFor({ state: 'visible', timeout: 60000 });
    await page.waitForFunction((name) => {
      const cards = [...document.querySelectorAll('.hc-attachment-card')];
      const card = cards.find((node) => node.innerText.includes(name));
      return Boolean(card && /准备失败|上传失败|附件存储暂不可用/.test(card.innerText));
    }, failedName, { timeout: 120000 });
    const failedText = await failedCard.innerText();
    evidence.failedAttachment = failedText;
    checks.attachmentUploadFailed = /准备失败/.test(failedText)
      && /ATTACHMENT_STORE_FAILED|ATTACHMENT_UPLOAD_FAILED|No space|ENOSPC|附件存储|上传失败|HTTP 5\d\d/i.test(failedText);
    const sendButton = page.locator('button[aria-label="发送消息"]').first();
    // The acceptance fact is the card state: a failed upload must not show
    // "可发送".  The button's disabled state is recorded as a diagnostic (the
    // SUT currently leaves it enabled while the send action is separately
    // blocked).
    checks.failedAttachmentNotMarkedSendable = /准备失败/.test(failedText)
      && !/可发送/.test(failedText);
    evidence.sendEnabledWithFailedAttachment = await sendButton.isEnabled();
    assert(checks.attachmentUploadFailed, `attachment failure is not visible: ${failedText}`);
    assert(checks.failedAttachmentNotMarkedSendable,
      'a failed attachment is still marked sendable');
    evidence.screenshots.push(await shot(page, dir, 'e2e18-02-full-attachment-failed'));

    // Export still works from the client even when the store cannot accept
    // writes; the download must contain the earlier readable history.
    await page.locator('details.hc-context-panel summary').first().click();
    const exportButton = page.getByRole('button', { name: '导出当前分支', exact: true }).first();
    await exportButton.waitFor({ state: 'visible', timeout: 30000 });
    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 60000 }),
      exportButton.click(),
    ]);
    const exported = await downloadText(download);
    evidence.exportBytes = Buffer.byteLength(exported);
    checks.exportDownloaded = Buffer.byteLength(exported) > 0;
    checks.exportContainsHistory = exported.includes(firstMessage)
      && exported.includes('E2E18-MOCK');
    assert(checks.exportDownloaded, 'export produced an empty download');
    assert(checks.exportContainsHistory, 'export omitted the readable history');
    evidence.screenshots.push(await shot(page, dir, 'e2e18-03-exported'));

    // localStorage quota branch: fill the origin until QuotaExceededError,
    // then change the draft so its debounced save must fail.
    const filled = await fillLocalStorage(page);
    evidence.localStorageFillKeys = filled.keys;
    checks.localStorageQuotaHit = filled.keys > 0;
    await composer(page).fill(`${DRAFT_TEXT}尾`);
    const draftWarning = page.locator('.hc-banner-warning')
      .filter({ hasText: '草稿暂未保存' }).first();
    await draftWarning.waitFor({ state: 'visible', timeout: 30000 });
    checks.draftWarningVisible = await draftWarning.isVisible();
    assert(checks.draftWarningVisible, 'the draft warning did not appear');

    const draftAtQuota = await composer(page).inputValue();
    const copyAction = draftWarning.getByRole('button', { name: '复制全部文字', exact: true }).first();
    await copyAction.click();
    const clipboard = await page.evaluate(() => navigator.clipboard.readText());
    checks.draftCopyWorks = clipboard === draftAtQuota;
    assert(checks.draftCopyWorks, 'copy draft action did not copy the draft');

    const saveDraftButton = draftWarning.getByRole('button', { name: '另存草稿', exact: true }).first();
    const [draftDownload] = await Promise.all([
      page.waitForEvent('download', { timeout: 60000 }),
      saveDraftButton.click(),
    ]);
    const savedDraft = await downloadText(draftDownload);
    checks.draftExportWorks = savedDraft === draftAtQuota;
    assert(checks.draftExportWorks, 'save draft action did not download the draft');
    evidence.screenshots.push(await shot(page, dir, 'e2e18-04-localstorage-quota'));

    await clearLocalStorage(page);
    await composer(page).press('End');
    await page.keyboard.insertText('。');
    await draftWarning.waitFor({ state: 'hidden', timeout: 30000 }).catch(() => {});
    const quotaRecoveredDraft = await composer(page).inputValue();
    checks.draftSaveRecoveredAfterQuota = await draftWarning.isHidden()
      && await page.evaluate((text) =>
        (window.localStorage.getItem('hc.workbench.v1') || '').includes(text),
      quotaRecoveredDraft.split('\n')[0]);
    // Keep the failure as evidence and continue: the restart/--check facts
    // are still collectable when local draft persistence does not recover.
    evidence.draftBeforeRestart = await composer(page).inputValue();
    evidence.sessionCountBeforeRestart = await page
      .locator('.wb-session-list .wb-list-button').count();

    // Free the tmpfs, then restart on the same data directory and port so the
    // browser origin (and therefore localStorage) is unchanged.
    await app.close();
    app = null;
    fs.rmSync(fillPath, { force: true });
    evidence.df.after = dfOf(root);
    checks.spaceFreed = evidence.df.after.usePercent < 100;
    assert(checks.spaceFreed, `space was not freed: ${JSON.stringify(evidence.df.after)}`);

    app = await startApp(appOptions);
    assertEqual(app.port, port, 'restart did not reuse the fixed port');
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    const sessionRows = page.locator('.wb-session-list .wb-list-button');
    await sessionRows.first().waitFor({ state: 'visible', timeout: 20000 });

    checks.initializedMarkerSurvives = fs.existsSync(path.join(app.dataDir, 'initialized.edn'));
    assert(checks.initializedMarkerSurvives, 'initialized.edn disappeared after restart');

    const sessionCount = await sessionRows.count();
    evidence.sessionCountAfterRestart = sessionCount;
    checks.noEmptySessionCreated = sessionCount === evidence.sessionCountBeforeRestart
      && sessionCount > 0;
    assert(checks.noEmptySessionCreated,
      `restart changed the session list: ${evidence.sessionCountBeforeRestart} -> ${sessionCount}`);

    // Select the session that actually owns the history.  A fresh store can
    // carry a previously created blank session, so "the first row" is not a
    // safe identity across a restart.
    let restoredSession = false;
    for (let index = 0; index < sessionCount; index += 1) {
      await sessionRows.nth(index).click();
      await composer(page).waitFor({ state: 'visible', timeout: 20000 });
      const found = await page.waitForFunction((text) =>
        [...document.querySelectorAll('.hc-message-out .hc-message-body')]
          .some((node) => node.innerText.includes(text)),
      firstMessage, { timeout: 8000 }).then(() => true).catch(() => false);
      if (found) {
        restoredSession = true;
        break;
      }
    }
    checks.restartHistoryReadable = restoredSession;
    assert(checks.restartHistoryReadable, 'no session restored the earlier history');
    await page.waitForFunction((prefix) =>
      [...document.querySelectorAll('textarea[aria-label="消息输入"]')]
        .some((node) => node.value.startsWith(prefix) && node.value.length > 1000),
    '长草稿 ', { timeout: 20000 });
    await page.waitForFunction(() =>
      [...document.querySelectorAll('.hc-message-in:not(.hc-message-process) .hc-message-body')]
        .some((node) => node.innerText.includes('E2E18-MOCK')),
    { timeout: 20000 });
    const restoredAssistants = await assistantTexts(page);
    checks.restartReplyReadable = restoredAssistants.some((text) => text.includes('E2E18-MOCK'));
    assert(checks.restartReplyReadable, 'the restored session lost the assistant reply');

    const restoredDraft = await composer(page).inputValue();
    evidence.restoredDraftLength = restoredDraft.length;
    checks.restartDraftRestored = restoredDraft === evidence.draftBeforeRestart;
    assert(checks.restartDraftRestored,
      `draft did not survive the restart: ${restoredDraft.length} vs ${evidence.draftBeforeRestart.length}`);

    // The failed upload is restored as a blocked card, which is correct: it
    // must not silently disappear.  Remove it explicitly before proving that
    // a new send can be accepted again.
    const restoredFailedCard = page.locator('.hc-attachment-card')
      .filter({ hasText: failedName }).first();
    await restoredFailedCard.waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
    const failedCardRestored = (await restoredFailedCard.count()) > 0;
    if (failedCardRestored) {
      const removeFailed = restoredFailedCard
        .getByRole('button', { name: `移除附件 ${failedName}`, exact: true }).first();
      if (await removeFailed.count()) await removeFailed.click();
      await restoredFailedCard.waitFor({ state: 'detached', timeout: 5000 }).catch(() => {});
    }
    checks.failedAttachmentRestoredVisible = failedCardRestored;
    checks.failedAttachmentRemovedAfterRestart = (await restoredFailedCard.count()) === 0;

    // A fresh save must be accepted again: localStorage for the draft and the
    // real server store for a new message.
    const recoveredDraft = `恢复后发送 ${MARKER}`;
    await composer(page).fill(recoveredDraft);
    await page.waitForFunction((text) =>
      (window.localStorage.getItem('hc.workbench.v1') || '').includes(text),
    recoveredDraft, { timeout: 20000 });
    checks.draftSaveRecovered = await page.evaluate((text) =>
      (window.localStorage.getItem('hc.workbench.v1') || '').includes(text), recoveredDraft);
    assert(checks.draftSaveRecovered, 'the draft did not save again after freeing space');
    const sendAfterRestart = page.locator('button[aria-label="发送消息"]').first();
    checks.sendEnabledAfterCleanup = await sendAfterRestart.isEnabled()
      && (await sendAfterRestart.getAttribute('aria-disabled')) !== 'true';
    checks.saveRecovered = false;
    if (checks.sendEnabledAfterCleanup) {
      try {
        const repliesBefore = await page.locator('.hc-message-in:not(.hc-message-process)').count();
        await sendAfterRestart.click();
        await page.waitForFunction((count) =>
          document.querySelectorAll('.hc-message-in:not(.hc-message-process)').length > count,
        repliesBefore, { timeout: 30000 });
        const recovered = await waitForAssistant(page, { timeout: 30000, minChars: 5, quietMs: 1500 });
        checks.saveRecovered = recovered.text.includes('E2E18-MOCK')
          && (await userTexts(page)).some((text) => text.includes(`恢复后发送 ${MARKER}`));
      } catch {
        checks.saveRecovered = false;
      }
    }
    evidence.sendBlockedAfterRestart = !checks.sendEnabledAfterCleanup;
    await ensureIdle(page, 30000);
    evidence.screenshots.push(await shot(page, dir, 'e2e18-05-restarted'));

    // The recovery gate: a data directory that was initialized once must not
    // be re-created as an empty store when its db is missing.
    await app.close();
    app = null;
    const dbPath = path.join(appOptions.root, 'data', 'db');
    const movedPath = path.join(appOptions.root, 'data', 'db-missing');
    assert(fs.existsSync(dbPath), 'store directory is missing before the recovery check');
    fs.renameSync(dbPath, movedPath);
    const checkResult = runAppCheck({ root: appOptions.root, port });
    evidence.recoveryCheck = {
      exitCode: checkResult.exitCode,
      stderr: checkResult.stderr.slice(-4000),
      stdout: checkResult.stdout.slice(-2000),
    };
    checks.recoveryCheckExitNonZero = checkResult.exitCode === 1;
    checks.recoveryCheckStoreMissing = /:STORE_MISSING_AFTER_INIT/.test(checkResult.stderr)
      || /:STORE_MISSING_AFTER_INIT/.test(checkResult.stdout);
    checks.recoveryEntryOffered = /:recovery-entry/.test(checkResult.stderr)
      && /:restore-from-backup/.test(checkResult.stderr);
    checks.storeNotRecreated = !fs.existsSync(dbPath);
    checks.markerAfterFailedCheck = fs.existsSync(path.join(appOptions.root, 'data', 'initialized.edn'));
    assert(checks.recoveryCheckExitNonZero, `--check exit=${checkResult.exitCode}`);
    assert(checks.recoveryCheckStoreMissing, '--check did not report :STORE_MISSING_AFTER_INIT');
    assert(checks.recoveryEntryOffered, '--check did not offer a recovery entry');
    assert(checks.storeNotRecreated, '--check re-created the missing store');
    assert(checks.markerAfterFailedCheck, '--check removed initialized.edn');

    evidence.ok = checksOk(checks);
    assert(evidence.ok, `failed checks: ${JSON.stringify(checks)}`);
    result = recordEvidence({
      dir,
      name: EVIDENCE_NAME,
      test: evidence.test,
      mode: evidence.mode,
      startedAt,
      extra: {
        os: evidence.os,
        namespace: evidence.namespace,
        tmpfsMiB: evidence.tmpfsMiB,
        marker: evidence.marker,
        df: evidence.df,
        blockedReason: null,
        banner: evidence.banner,
        draftLength: evidence.draftLength,
        restoredDraftLength: evidence.restoredDraftLength,
        saveLabels: evidence.saveLabels,
        sessionCountBeforeRestart: evidence.sessionCountBeforeRestart,
        sessionCountAfterRestart: evidence.sessionCountAfterRestart,
        failedAttachment: evidence.failedAttachment,
        exportBytes: evidence.exportBytes,
        localStorageFillKeys: evidence.localStorageFillKeys,
        recoveryCheck: evidence.recoveryCheck,
        appUrl: evidence.appUrl,
        mockUrl: evidence.mockUrl,
        chromeVersion: evidence.chromeVersion,
        screenshots: evidence.screenshots,
      },
      checks,
      ok: evidence.ok,
    });
    evidence.evidence = result.evidence;
  } catch (error) {
    evidence.error = error.message;
    const failed = recordEvidence({
      dir,
      name: EVIDENCE_NAME,
      test: evidence.test,
      mode: evidence.mode,
      startedAt,
      extra: {
        os: evidence.os,
        namespace: evidence.namespace,
        tmpfsMiB: evidence.tmpfsMiB,
        marker: evidence.marker,
        df: evidence.df,
        blockedReason: evidence.blockedReason,
        banner: evidence.banner,
        draftLength: evidence.draftLength,
        restoredDraftLength: evidence.restoredDraftLength,
        saveLabels: evidence.saveLabels,
        sessionCountBeforeRestart: evidence.sessionCountBeforeRestart,
        sessionCountAfterRestart: evidence.sessionCountAfterRestart,
        failedAttachment: evidence.failedAttachment,
        exportBytes: evidence.exportBytes,
        localStorageFillKeys: evidence.localStorageFillKeys,
        recoveryCheck: evidence.recoveryCheck,
        appUrl: evidence.appUrl,
        mockUrl: evidence.mockUrl,
        chromeVersion: evidence.chromeVersion,
        screenshots: evidence.screenshots,
        error: error.message,
      },
      checks,
      ok: false,
    });
    evidence.evidence = failed.evidence;
    throw error;
  } finally {
    try {
      if (app) await app.close();
    } catch {
      // The full tmpfs can make shutdown noisy; cleanup below still runs.
    }
    if (mock) await mock.close().catch(() => {});
    if (mounted && root) {
      const unmounted = run('umount', [root]);
      if (unmounted.status !== 0) run('umount', ['-l', root]);
    }
    if (root) {
      try {
        fs.rmSync(root, { recursive: true, force: true });
      } catch {
        // A lazy unmount or a dying JVM can keep the directory busy; the
        // namespace disappears with the test process anyway.
      }
    }
  }
});
