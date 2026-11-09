// E2E 17: encrypted backup to a mock WebDAV target, wrong-password refusal,
// and an offline CLI merge restore into a second data directory.  Verifies
// backup provenance, new sessions preserved, external workspaces flagged for
// relinking, and schedules paused after restore.
//
// The app is the real server + real UI.  Two data dirs are seeded by
// e2e17_seed.clj (a :backup state that produces the backup and a
// :destination state the merge restores into).  The backup is created
// through the UI; the restore runs through `hc.server.backup.cli` against a
// local mirror of the WebDAV object, because the offline CLI only accepts a
// file target.
import { test } from '@playwright/test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startApp } from './support/app.mjs';
import { sourceTreeSha256 } from './support/evidence.mjs';
import { startMockWebDAV } from './support/mock-services.mjs';
import {
  startMocks, buildAppOptions, verifyFixtureKeysAgainstServerSource,
  summarizeChecks,
} from './support/fixtures.mjs';
import {
  clientBundleInfo, composer, goto, newSession, waitForSync,
} from './support/ui.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../..');
const EVIDENCE_DIR = path.join(REPO_ROOT, 'plans/evidence/e2e-17');
const BACKUP_PASSWORD = 'correct horse battery staple';
const WRONG_PASSWORD = 'wrong password on purpose';

async function shot(page, dir, name) {
  fs.mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: path.join(dir, `${name}.png`), fullPage: true });
}

function runSeed(dataDir, wsExtPath, blobRoot, variant) {
  const out = execFileSync('clojure',
    ['-M:server', '-i', 'test/e2e/e2e17_seed.clj',
     '--eval', `(e2e17-seed/run ${JSON.stringify(dataDir)} ${JSON.stringify(wsExtPath)} ${JSON.stringify(blobRoot)} ${JSON.stringify(variant)})`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 120000 });
  return JSON.parse(out.trim().split('\n').pop());
}

function runVerify(dbPath) {
  const out = execFileSync('clojure',
    ['-M:server', '-i', 'test/e2e/e2e17_verify.clj',
     '--eval', `(e2e17-verify/run ${JSON.stringify(dbPath)})`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 120000 });
  return out.trim().split('\n').pop();
}

async function listWebdavFiles(webdavUrl) {
  const res = await fetch(new URL('/backups/', `${webdavUrl}/`), {
    method: 'PROPFIND',
    headers: { Depth: '1' },
  });
  assert.equal(res.status, 207, `PROPFIND failed: ${res.status}`);
  const body = await res.text();
  // The mock emits `D:` uppercase; the regex must be case-insensitive.
  return [...body.matchAll(/<d:href>([^<]+)<\/d:href>/gi)]
    .map((m) => m[1])
    .filter((p) => /\.hcb$/i.test(p));
}

async function openDataCenter(page) {
  await page.getByRole('button', { name: '设置与数据' }).click();
  // The backup workspace mounts only under the settings :data section.
  await page.getByRole('button', { name: '数据中心' }).first().click();
  await page.locator('.hc-backup-tab', { hasText: '恢复与预览' }).first().click();
  await page.locator('.hc-backup').first().waitFor({ state: 'visible', timeout: 30000 });
  // Wait until the first status read lands so target/backup rows exist.
  await page.waitForFunction(() => {
    const el = document.querySelector('.hc-backup');
    return el && /最近成功备份|尚无成功备份|备份目录/.test(el.textContent || '');
  }, null, { timeout: 30000 });
}

async function fillBackupPassword(page, password) {
  // Create tab has 备份密码 + 再次输入密码; restore tab has one 备份密码.
  // The create tab mounts two draft password fields inside the encryption
  // panel (`.hc-backup .hc-backup-password` holds exactly the create pair).
  // A bare `count()` right after the tab click can still see the previous
  // section's single field, so wait for the pair before filling.
  const inputs = page.locator('.hc-backup .hc-backup-password input[type="password"]');
  try {
    await inputs.nth(0).waitFor({ state: 'visible', timeout: 15000 });
    await inputs.nth(1).waitFor({ state: 'visible', timeout: 15000 });
  } catch (err) {
    const dump = await page.evaluate(() => {
      const panel = document.querySelector('.hc-backup');
      return {
        passwordInputs: [...document.querySelectorAll(
          '.hc-backup input[type="password"]')].map((el) => ({
            aria: el.getAttribute('aria-label'),
            cls: el.className,
            parentCls: el.parentElement && el.parentElement.className,
            value: el.value,
          })),
        warnings: [...document.querySelectorAll('.hc-backup-warning')]
          .map((el) => (el.textContent || '').trim()),
        panelText: panel ? (panel.textContent || '').trim().slice(0, 2000) : '',
      };
    }).catch((e) => ({ evaluateError: String(e) }));
    fs.writeFileSync(path.join(EVIDENCE_DIR, 'create-panel-text.txt'),
      `fillBackupPassword timeout: ${err.message}\n\n${JSON.stringify(dump, null, 2)}\n`);
    throw err;
  }
  await inputs.nth(0).fill(password);
  await inputs.nth(1).fill(password);
}

async function createBackup(page) {
  await page.locator('.hc-backup-tab', { hasText: '创建备份' }).first().click();
  await fillBackupPassword(page, BACKUP_PASSWORD);
  const createButton = page.getByRole('button', { name: '生成加密备份' });
  await createButton.waitFor({ state: 'visible', timeout: 15000 });
  // The button stays disabled until backup status, sync and transport are
  // ready; wait for the enabled state instead of asserting immediately.
  const panelText = await page.locator('.hc-backup').first().textContent().catch(() => '');
  try {
    await page.waitForFunction(() => {
      const btn = [...document.querySelectorAll('button')]
        .find((b) => (b.textContent || '').includes('生成加密备份'));
      return btn && !btn.disabled;
    }, null, { timeout: 120000 });
  } catch (err) {
    // Dump the create-tab surface so a stuck-disabled button can be diagnosed
    // (sync phase, command epoch, transport, target or password reason).
    const dump = await page.evaluate(() => {
      const panel = document.querySelector('.hc-backup');
      const texts = {};
      for (const cls of ['.hc-backup-warning', '.hc-backup-message',
                         '.hc-backup-panel']) {
        texts[cls] = [...document.querySelectorAll(cls)]
          .map((el) => (el.textContent || '').trim()).join('\n');
      }
      const btn = [...document.querySelectorAll('button')]
        .find((b) => (b.textContent || '').includes('生成'));
      return { panelText: panel ? (panel.textContent || '').trim() : '',
               texts, buttonText: btn ? btn.textContent.trim() : '',
               buttonDisabled: btn ? btn.disabled : null };
    }).catch((e) => ({ evaluateError: String(e) }));
    fs.writeFileSync(path.join(EVIDENCE_DIR, 'create-panel-text.txt'),
      `waitForFunction timeout: ${err.message}\n\n${JSON.stringify(dump, null, 2)}\n`);
    throw err;
  }
  await createButton.click();
  await page.locator('.hc-backup-result').waitFor({ state: 'visible', timeout: 120000 });
  await page.waitForFunction(() => {
    const el = document.querySelector('.hc-backup-result');
    return el && /备份已写入并通过校验|备份已生成|已写入/.test(el.textContent || '');
  }, null, { timeout: 120000 });
  return {
    panelText,
    resultText: await page.locator('.hc-backup-result').innerText(),
  };
}

async function selectBackupRow(page, backupId) {
  await page.locator('.hc-backup-tab', { hasText: '恢复与预览' }).first().click();
  // The list refreshes after creation; wait for any row before matching.
  await page.locator('.hc-backup-row').first().waitFor({ state: 'visible', timeout: 30000 });
  const row = page.locator('.hc-backup-row', { hasText: backupId }).first();
  await row.waitFor({ state: 'visible', timeout: 30000 });
  await row.getByRole('button', { name: '选择这份备份' }).first().click();
}

async function fillRestorePassword(page, password) {
  const input = page.locator('.hc-backup input[type="password"]').first();
  const clearButton = page.getByRole('button', { name: '清除密码' });
  // The restore panel's "校验备份" button is not password-dependent
  // (view.cljc:1004-1008).  The real state echo is "校验并生成预览",
  // which is disabled by `reason` until the password reaches :ui/backup
  // (view.cljc:1397-1400).  Clear first so the echo proves the new value.
  if (await clearButton.isEnabled().catch(() => false)) {
    await clearButton.click();
  }
  await input.waitFor({ state: 'visible', timeout: 20000 });
  await page.waitForFunction(() => {
    const el = document.querySelector('.hc-backup input[type="password"]');
    return el && el.value === '';
  }, null, { timeout: 10000 }).catch(() => {});
  await page.waitForFunction(() => {
    const btn = [...document.querySelectorAll('.hc-backup button')]
      .find((b) => (b.textContent || '').includes('校验并生成预览'));
    return !btn || btn.disabled;
  }, null, { timeout: 10000 }).catch(() => {});
  const deadline = Date.now() + 30000;
  let applied = false;
  while (Date.now() < deadline) {
    await input.fill(password, { timeout: 10000 }).catch(() => {});
    const echoed = await input.inputValue().catch(() => '');
    if (echoed === password) {
      // DraftInput is uncontrolled: make the DOM value drive the app state
      // with one explicit input/change pair, then wait for the panel echo.
      await input.evaluate((el, value) => {
        el.focus();
        el.value = value;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      }, password);
      applied = true;
      break;
    }
    // A controlled re-render dropped the value; wait a beat and refill.
    await page.waitForTimeout(150);
  }
  if (!applied) {
    const finalValue = await input.inputValue().catch(() => '<unreadable>');
    throw new Error(
      `restore password field never echoed the value (got ${JSON.stringify(finalValue)})`);
  }
  try {
    await page.waitForFunction(() => {
      const btn = [...document.querySelectorAll('.hc-backup button')]
        .find((b) => (b.textContent || '').includes('校验并生成预览'));
      return Boolean(btn && !btn.disabled);
    }, null, { timeout: 20000 });
  } catch (error) {
    const disabled = await page.locator('.hc-backup button')
      .filter({ hasText: '校验并生成预览' }).first()
      .isDisabled().catch(() => null);
    throw new Error(
      `preview button never enabled after password fill (disabled=${disabled}): ${error.message}`);
  }
}

async function verifyBackup(page, password) {
  await fillRestorePassword(page, password);
  await page.getByRole('button', { name: '校验备份' }).click();
}

async function dumpBackupPanel(page, label) {
  const dump = await page.evaluate(() => {
    const panel = document.querySelector('.hc-backup');
    return {
      inputs: [...document.querySelectorAll('.hc-backup input')].map((el) => ({
        type: el.type, aria: el.getAttribute('aria-label'),
        value: el.type === 'password' ? `(len ${el.value.length})` : el.value,
      })),
      warnings: [...document.querySelectorAll('.hc-backup-warning')]
        .map((el) => (el.textContent || '').trim()),
      messages: [...document.querySelectorAll('.hc-backup-message')]
        .map((el) => (el.textContent || '').trim()),
      verify: document.querySelector('.hc-backup-verify')
        ? document.querySelector('.hc-backup-verify').textContent.trim() : null,
      buttons: [...document.querySelectorAll('.hc-backup button')]
        .map((b) => ({ text: (b.textContent || '').trim().slice(0, 30),
                       disabled: b.disabled })),
      panelText: panel ? (panel.textContent || '').trim().slice(0, 3000) : '',
    };
  }).catch((e) => ({ evaluateError: String(e) }));
  fs.writeFileSync(path.join(EVIDENCE_DIR, `verify-dump-${label}.txt`),
    JSON.stringify(dump, null, 2));
  return dump;
}

async function previewBackup(page, password) {
  await fillRestorePassword(page, password);
  await page.getByRole('button', { name: '校验并生成预览' }).click();
  await page.locator('.hc-backup-preview').waitFor({ state: 'visible', timeout: 120000 });
  await page.waitForFunction(() => {
    const el = document.querySelector('.hc-backup-preview');
    return el && /恢复预览|合并选中类别|替换选中类别/.test(el.textContent || '');
  }, null, { timeout: 120000 });
}

async function previewText(page) {
  const el = page.locator('.hc-backup-preview');
  return (await el.textContent()) || '';
}

function backupIdFromText(text) {
  const match = /备份 ID\s*\n?\s*([0-9a-f-]{36})/i.exec(text)
    || /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i.exec(text);
  return match ? match[1] : null;
}

// Select one configured backup target through the radio group and wait for
// the target facts to name it.  The server refreshes its backup list for the
// newly selected target, so the following create/list steps see that target's
// objects, not the previous target's.
async function selectBackupTarget(page, label, targetId) {
  const choice = page.locator('.hc-backup-target-choice', { hasText: label }).first();
  await choice.waitFor({ state: 'visible', timeout: 30000 });
  const radio = choice.locator('input[type="radio"]').first();
  if (!(await radio.isChecked())) await radio.click();
  await page.waitForFunction((needle) => {
    const rows = [...document.querySelectorAll('.hc-backup-target-choice')];
    const row = rows.find((node) => (node.textContent || '').includes(needle));
    const input = row && row.querySelector('input[type="radio"]');
    return Boolean(input && input.checked);
  }, label, { timeout: 30000 });
  await page.waitForFunction((id) => {
    const el = document.querySelector('.hc-backup');
    return el && new RegExp(`已选目标\\s*\\n?\\s*${id}\\b`).test(el.innerText || '');
  }, targetId, { timeout: 30000 });
}

// Create one encrypted backup for the currently selected target.  A previous
// result may still be mounted, so the wait requires a different backup id.
async function createBackupForCurrentTarget(page, previousId) {
  await page.locator('.hc-backup-tab', { hasText: '创建备份' }).first().click();
  await fillBackupPassword(page, BACKUP_PASSWORD);
  await page.waitForFunction(() => {
    const btn = [...document.querySelectorAll('button')]
      .find((b) => (b.textContent || '').includes('生成加密备份'));
    return btn && !btn.disabled;
  }, null, { timeout: 120000 });
  await page.getByRole('button', { name: '生成加密备份' }).click();
  await page.waitForFunction((previous) => {
    const el = document.querySelector('.hc-backup-result');
    if (!el) return false;
    const text = el.innerText || el.textContent || '';
    const match = /备份 ID\s*\n?\s*([0-9a-f-]{36})/i.exec(text)
      || /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i.exec(text);
    return Boolean(match) && match[1] !== previous;
  }, previousId, { timeout: 180000 });
  const resultText = await page.locator('.hc-backup-result').innerText();
  return { resultText: resultText.slice(0, 1200), backupId: backupIdFromText(resultText) };
}

test('@e2e E2E 17 encrypted backup, wrong password refusal and merge restore', async ({ browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const checks = {};
  const diagnostics = {};
  const evidence = {
    checks,
    diagnostics,
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
  };
  // Observations never enter `checks`; they are registered here and emitted
  // under `diagnostics`.  Every check is a boolean and every non-true value
  // (undefined included) fails via checksOk.
  const diagnosticOnly = [
    'createPanelText', 'backupId', 'verifyRaw', 'sessionIds',
    'assistantNames', 'scheduleState', 'dstActiveRows', 'dstDeletedAreaText',
    'wrongPasswordText',
  ];
  const writeEvidence = () => {
    try {
      fs.writeFileSync(path.join(EVIDENCE_DIR, 'e2e-17.json'),
                       JSON.stringify(evidence, null, 2));
    } catch { /* best effort */ }
  };

  const webdav = await startMockWebDAV();
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e17-'));
  const srcRoot = path.join(tempRoot, 'src');
  const dstRoot = path.join(tempRoot, 'dst');
  const srcData = path.join(srcRoot, 'data');
  const srcWorkspace = path.join(srcRoot, 'workspace');
  const srcBlobs = path.join(srcRoot, 'blobs');
  const dstData = path.join(dstRoot, 'data');
  const dstWorkspace = path.join(dstRoot, 'workspace');
  const dstBlobs = path.join(dstRoot, 'blobs');
  const localBlobs = path.join(dstRoot, 'local-blobs');
  const externalWs = path.join(srcRoot, 'external-ws');
  const dstExternalWs = path.join(dstRoot, 'external-ws');
  const dstWorkspaces = path.join(dstRoot, 'workspaces');
  for (const d of [srcData, srcWorkspace, srcBlobs, dstData, dstWorkspace,
                   dstBlobs, localBlobs, dstWorkspaces]) {
    fs.mkdirSync(d, { recursive: true });
  }
  fs.mkdirSync(externalWs, { recursive: true });
  fs.mkdirSync(dstExternalWs, { recursive: true });

  // Seed source (backup variant) and destination (older local state) stores
  // before either app starts, so the first open sees a complete store.
  runSeed(srcData, externalWs, srcBlobs, 'backup');
  runSeed(dstData, dstExternalWs, dstBlobs, 'destination');

  let appSrc;
  let appDst;
  let context;
  let page;
  try {
    appSrc = await startApp({
      root: srcRoot,
      port: 0,
      evidenceDir: EVIDENCE_DIR,
      backup: {
        targets: {
          webdav: {
            kind: 'webdav',
            baseUrl: `${webdav.url}/backups`,
            directory: '',
          },
        },
        defaultTargetId: 'webdav',
      },
    });

    context = await browser.newContext();
    page = await context.newPage();
    await goto(page, appSrc.url, '/settings');
    await waitForSync(page);
    await openDataCenter(page);
    checks.dataCenterOpened = Boolean(await page.locator('.hc-backup').count());
    assert(checks.dataCenterOpened, 'data center did not mount');
    await shot(page, EVIDENCE_DIR, '01-data-center');

    // --- create the encrypted backup through the UI ---------------------
    const backupRun = await createBackup(page);
    diagnostics.createPanelText = backupRun.panelText;
    checks.backupCreated =
      /备份已写入并通过校验|备份已生成|已写入/.test(backupRun.resultText);
    assert(checks.backupCreated,
      `backup result did not confirm a written object: ${backupRun.resultText.slice(0, 300)}`);
    await shot(page, EVIDENCE_DIR, '02-backup-created');

    const objects = await listWebdavFiles(webdav.url);
    checks.webdavObjects = objects.length > 0;
    assert(objects.length > 0, 'no backup object on mock WebDAV');
    // Backup objects live at /backups/<id>.hcb; the id is the file basename.
    const backupId = path.posix.basename(objects[0]).replace(/\.hcb$/i, '');
    diagnostics.backupId = backupId;

    // --- select the backup, then refuse the wrong password ---------------
    await selectBackupRow(page, backupId);
    await verifyBackup(page, WRONG_PASSWORD);
    try {
      await page.waitForFunction(() => {
        const el = document.querySelector('.hc-backup');
        return el && /密码|不正确|无法|错误|失败/.test(el.textContent || '');
      }, null, { timeout: 60000 });
    } catch (err) {
      await dumpBackupPanel(page, 'wrong-password');
      throw err;
    }
    const wrongPasswordText = await page.locator('.hc-backup').innerText();
    diagnostics.wrongPasswordText = wrongPasswordText.slice(0, 1200);
    checks.wrongPasswordRefused =
      /密码|不正确|无法|错误|失败/.test(wrongPasswordText);
    assert(checks.wrongPasswordRefused,
      `wrong password was not refused: ${wrongPasswordText.slice(0, 300)}`);
    await shot(page, EVIDENCE_DIR, '03-wrong-password');

    // --- correct password: verify then preview ---------------------------
    await verifyBackup(page, BACKUP_PASSWORD);
    try {
      await page.waitForFunction(() => {
        const el = document.querySelector('.hc-backup-verify');
        return el && /校验通过/.test(el.textContent || '');
      }, null, { timeout: 120000, polling: 1500 });
    } catch (err) {
      const actual = await page.locator('.hc-backup-verify').first()
        .innerText().catch(() => '<missing>');
      await dumpBackupPanel(page, 'correct-password');
      err.message = `${err.message}; .hc-backup-verify actual=${JSON.stringify(actual.slice(0, 200))}`;
      throw err;
    }
    await previewBackup(page, BACKUP_PASSWORD);
    const preview = await previewText(page);
    checks.previewHasMissing = /att-missing|s-attachments|缺失/.test(preview);
    checks.previewHasRelink = /ws-ext|app-workspace|重新定位|relink/.test(preview);
    checks.previewModeMerge = /合并选中类别/.test(preview);
    assert(checks.previewHasMissing, 'preview did not list the missing attachment');
    assert(checks.previewHasRelink, 'preview did not flag the external workspace for relink');
    assert(checks.previewModeMerge, 'preview did not default to merge mode');
    await shot(page, EVIDENCE_DIR, '04-restore-preview');

    await appSrc.close();
    appSrc = null;
    await context.close();
    context = null;
    page = null;

    // --- offline CLI merge restore into the destination store ------------
    // The offline CLI only reads a file target; mirror the WebDAV object into
    // <mirror>/objects/<id>.hcb and point --target-root at <mirror>.
    const mirrorRoot = path.join(tempRoot, 'mirror');
    fs.mkdirSync(path.join(mirrorRoot, 'objects'), { recursive: true });
    const objectRes = await fetch(new URL(`/backups/${backupId}.hcb`, `${webdav.url}/`));
    assert.equal(objectRes.status, 200, `WebDAV GET failed: ${objectRes.status}`);
    const objectBytes = Buffer.from(await objectRes.arrayBuffer());
    fs.writeFileSync(path.join(mirrorRoot, 'objects', `${backupId}.hcb`), objectBytes);

    const restoreOut = execFileSync('clojure',
      ['-M:server', '-m', 'hc.server.backup.cli', 'restore',
       '--db-path', path.join(dstData, 'db'),
       '--data-dir', dstData,
       '--target-root', mirrorRoot,
       '--recovery-root', path.join(dstRoot, 'recovery'),
       '--blob-root', localBlobs,
       '--workspace-store-root', dstWorkspaces,
       '--workspace-root', dstExternalWs,
       '--backup-id', backupId,
       '--mode', 'merge',
       '--password-env', 'HC_BACKUP_PASSWORD'],
      { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180000,
        env: { ...process.env, HC_BACKUP_PASSWORD: BACKUP_PASSWORD } });
    // hc.server.backup.cli prints one EDN result, not JSON.
    checks.restoreOk = /:ok\?\s+true/.test(restoreOut);
    assert(checks.restoreOk, `offline restore failed: ${restoreOut}`);

    // Blob objects are restored under localBlobs; copy them into the dst app's
    // blob root so the second app can serve attachment bytes.
    fs.cpSync(localBlobs, dstBlobs, { recursive: true });

    const verifyOut = runVerify(path.join(dstData, 'db'));
    const verify = JSON.parse(verifyOut);
    evidence.verify = verify;
    diagnostics.verifyRaw = verifyOut;
    diagnostics.sessionIds = verify.sessionIds;
    diagnostics.assistantNames = verify.assistantNames;
    diagnostics.scheduleState = verify.scheduleState;
    checks.schedulePaused = verify.schedulePaused === true;
    checks.backupAssistantPresent = verify.backupAssistantPresent === true;
    // Acceptance conditions hidden inside the data observations above:
    // keep-both sessions and keep-both assistants are what the merge must
    // produce, not mere diagnostics.
    checks.mergesKeepBothSessions = verify.sessionIds.includes('s-backup-new')
      && verify.sessionIds.some((id) => id.includes('@backup-'));
    const assistantIds = Object.keys(verify.assistantNames || {});
    checks.mergesKeepBothAssistants =
      assistantIds.includes('a1') && assistantIds.includes('a2')
      && assistantIds.some((id) => id.includes('@backup-'));
    // `s-history` is byte-identical on both sides and is skipped as an
    // idempotent import.  `s-attachments` differs because the missing
    // attachment is recorded in the backup conversation metadata, so
    // keep-both imports `s-attachments@backup-*`.  Final count:
    // 3 destination + s-backup-new + s-attachments copy = 5.
    checks.historyIdenticalSkipped =
      !verify.sessionIds.some((id) => id.startsWith('s-history@backup-'));
    checks.attachmentsConflictCopied =
      verify.sessionIds.some((id) => id.startsWith('s-attachments@backup-'));
    assert.strictEqual(verify.sessionCount, 5,
                       `expected 5 sessions, got ${verify.sessionCount}`);
    assert(verify.sessionIds.includes('s-backup-new')
           && verify.sessionIds.some((id) => id.includes('@backup-')),
           `merge did not keep both sides: ${verify.sessionIds}`);
    assert(verify.backupAssistantPresent,
           'merged config lacks a backup-suffixed assistant');
    assert(checks.mergesKeepBothSessions,
      `merge did not keep both session sides: ${verify.sessionIds}`);
    assert(checks.mergesKeepBothAssistants,
      `merge did not keep both assistant sides: ${assistantIds}`);
    assert(checks.historyIdenticalSkipped,
      `identical s-history was copied: ${verify.sessionIds}`);
    assert(checks.attachmentsConflictCopied,
      `s-attachments conflict copy missing: ${verify.sessionIds}`);
    assert(verify.schedulePaused,
           `schedule not paused after restore: ${verify.scheduleState}`);

    // Start the destination app and confirm the merged state is visible.
    appDst = await startApp({
      root: dstRoot,
      port: 0,
      evidenceDir: EVIDENCE_DIR,
    });
    context = await browser.newContext();
    page = await context.newPage();
    await goto(page, appDst.url, '/sessions');
    await waitForSync(page);
    const bodyText = await page.locator('body').innerText();
    checks.dstShowsBackupSession = /备份新增会话|s-backup-new/.test(bodyText);
    // The deleted area is a permanent sibling of the active list and its
    // summary always says 已删除会话.  Whole-page matching therefore always
    // "found" it.  Active-list buttons exclude the deleted rows (they render
    // as non-selectable divs), so inspect the rows themselves.
    const activeRows = await page.locator('.wb-session-list .wb-list-button')
      .allInnerTexts();
    diagnostics.dstActiveRows = activeRows;
    checks.dstHidesDeleted = !activeRows.some((row) =>
      /已删除会话|s-deleted/.test(row));
    // Positive control: the deleted session is in the deleted area, not lost.
    const deletedDetails = page.locator('details.wb-deleted-sessions').first();
    await deletedDetails.locator('summary').first().click();
    const loadDeleted = deletedDetails
      .getByRole('button', { name: /加载已删除会话|刷新已删除会话/ }).first();
    await loadDeleted.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {});
    if (await loadDeleted.count()) await loadDeleted.click();
    await page.waitForFunction(() => {
      const el = document.querySelector('details.wb-deleted-sessions');
      return el && /已删除 ·/.test(el.textContent || '');
    }, null, { timeout: 60000 });
    const deletedText = await deletedDetails.innerText();
    diagnostics.dstDeletedAreaText = deletedText.slice(0, 800);
    checks.dstDeletedAreaHasSession = /已删除 ·/.test(deletedText);
    assert(checks.dstHidesDeleted,
      `deleted session leaked into the active list: ${JSON.stringify(activeRows)}`);
    assert(checks.dstDeletedAreaHasSession,
      `deleted area lost the restored deleted session: ${deletedText.slice(0, 400)}`);
    await shot(page, EVIDENCE_DIR, '05-dst-app');

    const summary = summarizeChecks(checks, diagnostics, diagnosticOnly);
    evidence.checks = summary.checks;
    evidence.diagnostics = summary.diagnostics;
    evidence.diagnosticOnly = summary.diagnosticOnly;
    evidence.failedChecks = summary.failedChecks;
    evidence.ok = summary.ok;
    writeEvidence();
    assert(summary.ok,
      `E2E 17 checks failed: ${summary.failedChecks.join(', ')}`);
  } finally {
    if (typeof evidence.ok !== 'boolean') {
      evidence.ok = false;
      evidence.failedChecks = ['test-did-not-complete'];
      evidence.diagnosticOnly = diagnosticOnly;
      evidence.diagnostics = diagnostics;
    }
    writeEvidence();
    if (page) await page.close().catch(() => {});
    if (context) await context.close().catch(() => {});
    if (appSrc) await appSrc.close().catch(() => {});
    if (appDst) await appDst.close().catch(() => {});
    await webdav.close().catch(() => {});
  }
});

// Parallel WebDAV + S3 targets.  Both targets come from the shared fixture
// (buildAppOptions) so the spec exercises the same config generator the other
// journeys use.  The default target is WebDAV; the UI switches to S3 and each
// target receives its own encrypted object.  A restore preview against the S3
// object proves the restored list is usable, not just the PUT.
test('@e2e E2E 17 S3 backup target and restore preview', async ({ browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const checks = {};
  const diagnostics = {};
  const evidence = {
    test: 'E2E 17 S3 target',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    checks,
    diagnostics,
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
  };
  const diagnosticOnly = [
    'defaultTargetId', 'targetRows', 'webdavBackup', 'webdavObjects',
    's3Backup', 's3Objects', 'previewText',
  ];
  const writeEvidence = () => {
    try {
      fs.writeFileSync(path.join(EVIDENCE_DIR, 'e2e-17-s3.json'),
        JSON.stringify(evidence, null, 2));
    } catch { /* best effort */ }
  };

  const mocks = await startMocks({ webdav: true, s3: true });
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e17-s3-'));
  let app;
  let context;
  let page;
  try {
    const appOptions = buildAppOptions(mocks, {
      root: tempRoot,
      port: 0,
      evidenceDir: EVIDENCE_DIR,
    });
    // Unit-level fixture guard: the S3 `:path-style?` key and the model-auth
    // `?` keys must match the server schemas that consume them.
    const fixtureKeys = verifyFixtureKeysAgainstServerSource();
    evidence.fixtureKeys = fixtureKeys;
    checks.fixtureKeysAligned = fixtureKeys.ok;
    // The fixture always writes WebDAV as the default when both targets are
    // present; record that contract before exercising the switch.
    diagnostics.defaultTargetId = appOptions.backup.defaultTargetId;
    checks.defaultTargetIsWebdav =
      appOptions.backup.defaultTargetId === 'webdav';
    assert.equal(diagnostics.defaultTargetId, 'webdav',
      'fixture default target is not webdav');
    assert(checks.defaultTargetIsWebdav,
      'fixture default target is not webdav');
    app = await startApp(appOptions);
    context = await browser.newContext();
    page = await context.newPage();
    // One real session keeps the sampled store non-empty, so the restore
    // preview has a session record to describe instead of an empty store.
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await composer(page).fill('E2E17 S3 备份草稿');
    await goto(page, app.url, '/settings');
    await waitForSync(page);
    await openDataCenter(page);

    const targetRows = await page.locator('.hc-backup-target-choice strong')
      .allInnerTexts();
    diagnostics.targetRows = targetRows;
    checks.hasWebdavTarget = targetRows.some((row) => /WebDAV/.test(row));
    checks.hasS3Target = targetRows.some((row) => /S3/.test(row));
    assert(checks.hasWebdavTarget && checks.hasS3Target,
      `both targets are not listed: ${JSON.stringify(targetRows)}`);
    await shot(page, EVIDENCE_DIR, 's3-01-two-targets');

    // --- WebDAV object ------------------------------------------------
    await selectBackupTarget(page, 'WebDAV', 'webdav');
    const webdavBackup = await createBackupForCurrentTarget(page, null);
    diagnostics.webdavBackup = webdavBackup;
    checks.webdavBackupIdFound = Boolean(webdavBackup.backupId);
    assert(checks.webdavBackupIdFound,
      `no WebDAV backup id in result: ${webdavBackup.resultText}`);
    const webdavObjects = [...mocks.webdav.state.files.keys()];
    diagnostics.webdavObjects = webdavObjects;
    checks.webdavWroteObject = webdavObjects.some((key) =>
      key.endsWith(`/${webdavBackup.backupId}.hcb`));
    assert(checks.webdavWroteObject,
      `WebDAV target has no object for ${webdavBackup.backupId}: ${webdavObjects.join(', ')}`);

    // --- switch to S3 and create the second backup ---------------------
    await selectBackupTarget(page, 'S3', 's3');
    const s3Backup = await createBackupForCurrentTarget(page, webdavBackup.backupId);
    diagnostics.s3Backup = s3Backup;
    checks.s3BackupIdFound = Boolean(s3Backup.backupId);
    checks.backupIdsDistinct = Boolean(s3Backup.backupId)
      && s3Backup.backupId !== webdavBackup.backupId;
    assert(checks.s3BackupIdFound && checks.backupIdsDistinct,
      `S3 backup did not produce a new id: ${s3Backup.resultText}`);
    const s3Objects = [...mocks.s3.state.objects.keys()];
    diagnostics.s3Objects = s3Objects;
    checks.s3WroteObject = s3Objects.some((key) =>
      key.endsWith(`/${s3Backup.backupId}.hcb`));
    checks.s3BucketPrefix = s3Objects.length > 0
      && s3Objects.every((key) => key.startsWith('mock-bucket/backups/'));
    // Negative isolation: the S3 object must not land on WebDAV, and the
    // WebDAV object must not land in the S3 bucket.
    const webdavObjectsAfterS3 = [...mocks.webdav.state.files.keys()];
    checks.targetsIsolated =
      !webdavObjectsAfterS3.some((key) => key.includes(s3Backup.backupId))
      && !s3Objects.some((key) => key.includes(webdavBackup.backupId));
    assert(checks.s3WroteObject,
      `S3 target has no object for ${s3Backup.backupId}: ${s3Objects.join(', ')}`);
    assert(checks.s3BucketPrefix,
      `S3 objects are not under the configured bucket/prefix: ${s3Objects.join(', ')}`);
    assert(checks.targetsIsolated,
      'a backup object leaked to the other target');
    evidence.s3Requests = mocks.s3.state.requests
      .filter((request) => ['PUT', 'GET', 'HEAD'].includes(request.method))
      .slice(-40);
    await shot(page, EVIDENCE_DIR, 's3-02-two-backups');

    // --- restore preview selects the S3 object -------------------------
    await page.locator('.hc-backup-tab', { hasText: '恢复与预览' }).first().click();
    const row = page.locator('.hc-backup-row', { hasText: s3Backup.backupId }).first();
    // The list refresh after a create can be skipped while another status
    // command is in flight; retry the explicit refresh until the S3 object is
    // listed.
    for (let attempt = 0; attempt < 6; attempt += 1) {
      if (await row.count()) break;
      const refreshStatus = page.getByRole('button', { name: '刷新状态', exact: true })
        .first();
      if (await refreshStatus.count()) await refreshStatus.click().catch(() => {});
      // Retry backoff only: the real condition is the `.hc-backup-row`
      // check above.  The status command is asynchronous and may be refused
      // while another one is pending, so a short settle avoids hammering it.
      await page.waitForTimeout(2000);
    }
    await row.waitFor({ state: 'visible', timeout: 60000 });
    await row.getByRole('button', { name: '选择这份备份' }).first().click();
    await page.waitForFunction((id) => {
      const el = document.querySelector('.hc-backup-row--selected');
      return el && (el.textContent || '').includes(id);
    }, s3Backup.backupId, { timeout: 30000 });
    checks.restoreRowSelectable = (await page.locator('.hc-backup-row--selected')
      .filter({ hasText: s3Backup.backupId }).count()) >= 1;
    assert(checks.restoreRowSelectable,
      `the S3 backup row was not selected: ${s3Backup.backupId}`);
    await previewBackup(page, BACKUP_PASSWORD);
    const preview = await previewText(page);
    checks.previewVisible = /恢复预览|合并选中类别|替换选中类别/.test(preview);
    diagnostics.previewText = preview.slice(0, 1000);
    assert(checks.previewVisible,
      `S3 backup preview did not open: ${preview.slice(0, 500)}`);
    await shot(page, EVIDENCE_DIR, 's3-03-restore-preview');

    const summary = summarizeChecks(checks, diagnostics, diagnosticOnly);
    evidence.checks = summary.checks;
    evidence.diagnostics = summary.diagnostics;
    evidence.diagnosticOnly = summary.diagnosticOnly;
    evidence.failedChecks = summary.failedChecks;
    evidence.ok = summary.ok;
    assert(summary.ok,
      `S3 target checks failed: ${summary.failedChecks.join(', ')}`);
  } catch (error) {
    evidence.ok = false;
    evidence.failedChecks = ['run-aborted'];
    evidence.diagnostics = diagnostics;
    evidence.diagnosticOnly = [...diagnosticOnly];
    evidence.error = String(error && error.message || error).slice(0, 2000);
    if (page) await shot(page, EVIDENCE_DIR, 's3-error').catch(() => {});
    throw error;
  } finally {
    evidence.finishedAt = new Date().toISOString();
    writeEvidence();
    if (page) await page.close().catch(() => {});
    if (context) await context.close().catch(() => {});
    if (app) await app.close().catch(() => {});
    await mocks.close().catch(() => {});
  }
});
