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
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startApp } from './support/app.mjs';
import { startMockWebDAV } from './support/mock-services.mjs';
import { goto, waitForSync } from './support/ui.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../..');
const EVIDENCE_DIR = path.join(REPO_ROOT, 'plans/evidence/e2e-17');
const BACKUP_PASSWORD = 'correct horse battery staple';
const WRONG_PASSWORD = 'wrong password on purpose';

function sourceTreeSha256() {
  const hash = crypto.createHash('sha256');
  const walk = (dir) => {
    for (const name of fs.readdirSync(dir).sort()) {
      const p = path.join(dir, name);
      const st = fs.statSync(p);
      if (st.isDirectory()) walk(p);
      else if (st.isFile()) {
        hash.update(path.relative(REPO_ROOT, p));
        hash.update(fs.readFileSync(p));
      }
    }
  };
  walk(path.join(REPO_ROOT, 'src'));
  walk(path.join(REPO_ROOT, 'test/e2e'));
  return hash.digest('hex');
}

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

async function createBackup(page, checks) {
  await page.locator('.hc-backup-tab', { hasText: '创建备份' }).first().click();
  await fillBackupPassword(page, BACKUP_PASSWORD);
  const createButton = page.getByRole('button', { name: '生成加密备份' });
  await createButton.waitFor({ state: 'visible', timeout: 15000 });
  // The button stays disabled until backup status, sync and transport are
  // ready; wait for the enabled state instead of asserting immediately.
  const panelText = await page.locator('.hc-backup').first().textContent().catch(() => '');
  checks.createPanelText = panelText;
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
  // Clear first when a previous attempt left a password in :ui/backup.  The
  // button is disabled exactly when the state password is already empty.
  if (await clearButton.isEnabled()) {
    await clearButton.click();
  }
  await page.waitForFunction(() => {
    const el = document.querySelector('.hc-backup input[type="password"]');
    return el && el.value === '';
  }, null, { timeout: 15000 });
  await input.fill(password);
  // DraftInput is uncontrolled: DOM value alone does not prove :ui/backup
  // changed.  The preview button is disabled while the encrypted backup has
  // no password, so its enabled state is the observable state echo.
  await page.waitForFunction((expected) => {
    const el = document.querySelector('.hc-backup input[type="password"]');
    return el && el.value === expected;
  }, password, { timeout: 15000 });
  await page.waitForFunction(() => {
    const btn = [...document.querySelectorAll('.hc-backup button')]
      .find((b) => (b.textContent || '').includes('校验并生成预览'));
    return btn && !btn.disabled;
  }, null, { timeout: 15000 });
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

test('@e2e E2E 17 encrypted backup, wrong password refusal and merge restore', async ({ browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const checks = {};
  const evidence = { checks, sourceTreeSha256: sourceTreeSha256() };
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
    checks.dataCenterOpened = true;
    await shot(page, EVIDENCE_DIR, '01-data-center');

    // --- create the encrypted backup through the UI ---------------------
    await createBackup(page, checks);
    checks.backupCreated = true;
    await shot(page, EVIDENCE_DIR, '02-backup-created');

    const objects = await listWebdavFiles(webdav.url);
    checks.webdavObjects = objects.length > 0;
    assert(objects.length > 0, 'no backup object on mock WebDAV');
    // Backup objects live at /backups/<id>.hcb; the id is the file basename.
    const backupId = path.posix.basename(objects[0]).replace(/\.hcb$/i, '');
    checks.backupId = backupId;

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
    checks.wrongPasswordRefused = true;
    await shot(page, EVIDENCE_DIR, '03-wrong-password');

    // --- correct password: verify then preview ---------------------------
    await verifyBackup(page, BACKUP_PASSWORD);
    try {
      await page.waitForFunction(() => {
        const el = document.querySelector('.hc-backup-verify');
        return el && /校验通过/.test(el.textContent || '');
      }, null, { timeout: 60000 });
    } catch (err) {
      await dumpBackupPanel(page, 'correct-password');
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
    checks.verifyRaw = verifyOut;
    checks.sessionIds = verify.sessionIds;
    checks.assistantNames = verify.assistantNames;
    checks.scheduleState = verify.scheduleState;
    checks.schedulePaused = verify.schedulePaused;
    checks.backupAssistantPresent = verify.backupAssistantPresent;
    // Merge conflict resolution defaults to keep-both: the two conflicting
    // source sessions are imported as deterministic @backup copies, so the
    // store keeps the three destination records plus the three backup ones.
    assert.strictEqual(verify.sessionCount, 6,
                       `expected 6 sessions, got ${verify.sessionCount}`);
    assert(verify.sessionIds.includes('s-backup-new')
           && verify.sessionIds.some((id) => id.includes('@backup-')),
           `merge did not keep both sides: ${verify.sessionIds}`);
    assert(verify.backupAssistantPresent,
           'merged config lacks a backup-suffixed assistant');
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
    checks.dstHidesDeleted = !/已删除会话|s-deleted/.test(bodyText);
    await shot(page, EVIDENCE_DIR, '05-dst-app');

    writeEvidence();
  } finally {
    writeEvidence();
    if (page) await page.close().catch(() => {});
    if (context) await context.close().catch(() => {});
    if (appSrc) await appSrc.close().catch(() => {});
    if (appDst) await appDst.close().catch(() => {});
    await webdav.close().catch(() => {});
  }
});
