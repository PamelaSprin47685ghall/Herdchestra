// E2E 22: cross-device migration — seed an old-device store, create a real
// WebDAV backup through the settings UI (including deleted sessions), mirror
// the mock WebDAV objects to a local file target, restore offline through
// `hc.server.backup.cli`, then verify the merged store on a fresh device.
//
// Flow:
//   seed :source  -> startApp -> settings/data-center -> new WebDAV target
//     -> create encrypted backup (include-deleted checked)
//   mirror mock WebDAV files -> <mirror>/objects/*.hcb
//   clojure -M:server -m hc.server.backup.cli restore --target-root <mirror>
//     --db-path <dst>/db --include-deleted? true ...
//   e2e22_verify.clj on <dst>/db + <data>/extensions
//   seed :destination is merged BEFORE restore; startApp on the destination
//   root verifies the UI (sessions, deleted area, memory, MCP off, no backup
//   target, no fleet badge).
//
// Evidence: plans/evidence/e2e-22/ (JSON + screenshots + bundle/tree hashes).
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { startApp, REPO_ROOT, clojureCommand } from './support/app.mjs';
import { startMockWebDAV } from './support/mock-services.mjs';
import { summarizeChecks } from './support/fixtures.mjs';
import {
  assert as assertCheck, goto, shot, waitForSync, writeJson,
  clientBundleInfo,
} from './support/ui.mjs';
import { sourceTreeSha256 } from './support/evidence.mjs';

const EVIDENCE_DIR = path.join(REPO_ROOT, 'plans/evidence/e2e-22');
const BACKUP_PASSWORD = 'e2e22 migration passphrase';

// hc.server.backup.cli prints one EDN map via pr-str, not JSON.  Minimal
// reader for the shapes this test needs: maps, vectors, strings, keywords,
// numbers, booleans, nil.
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
        out[key] = parse();
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
  // Normalize keyword map keys to plain JS property names so callers can
  // read `result.ok?` style fields without the leading colon.
  const normalize = (v) => {
    if (Array.isArray(v)) return v.map(normalize);
    if (v && typeof v === 'object') {
      return Object.fromEntries(Object.entries(v).map(([k, val]) => [
        typeof k === 'string' && k.startsWith(':') ? k.slice(1) : k,
        normalize(val),
      ]));
    }
    return v;
  };
  return normalize(value);
}

function runSeed(dataDir, blobRoot, extensionsRoot, variant) {
  const out = execFileSync(clojureCommand(),
    ['-M:server', '-i', 'test/e2e/e2e22_seed.clj',
     '--eval', `(e2e22-seed/run ${JSON.stringify(dataDir)} ${JSON.stringify(blobRoot)} ${ednOrNil(extensionsRoot)} ${JSON.stringify(variant)})`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 300000 });
  return JSON.parse(out.trim().split('\n').pop());
}

// JSON.stringify(null) emits `null`, which is not an EDN literal; the seed
// script takes nil for "no extensions root".
function ednOrNil(value) {
  return value == null ? 'nil' : JSON.stringify(value);
}

function runVerify(dbPath, extensionsRoot) {
  const out = execFileSync(clojureCommand(),
    ['-M:server', '-i', 'test/e2e/e2e22_verify.clj',
     '--eval', `(e2e22-verify/run ${JSON.stringify(dbPath)} ${JSON.stringify(extensionsRoot)})`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 300000 });
  return normalizeKeys(JSON.parse(out.trim().split('\n').pop()));
}

// The verify script emits Clojure keyword names (`sessionId`, `pinned?`,
// `connection-id`, `mcp-filesystem`, ...).  Normalize every map key to a
// plain camelCase JS property so assertions read naturally.
function normalizeKeys(value) {
  if (Array.isArray(value)) return value.map(normalizeKeys);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [
      String(k)
        .replace(/^:+/, '')
        .replace(/\?$/, '')
        .replace(/-([a-z])/g, (_, c) => c.toUpperCase())
        .replace(/_([a-z])/g, (_, c) => c.toUpperCase()),
      normalizeKeys(v),
    ]));
  }
  return value;
}

function runBackupCli(args, env = {}) {
  return execFileSync(clojureCommand(),
    ['-M:server', '-m', 'hc.server.backup.cli', ...args],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 600000,
      env: { ...process.env, ...env } });
}

async function openDataCenter(page) {
  await page.getByRole('button', { name: '设置与数据' }).first().click();
  await page.locator('.hc-settings').first().waitFor({ state: 'visible', timeout: 30000 });
  await page.locator('.hc-tab', { hasText: '数据中心' }).first().click();
  await page.locator('.hc-backup').first().waitFor({ state: 'visible', timeout: 30000 });
}

// Hash-only navigation inside an already-loaded SPA: `page.goto` with
// `waitUntil: 'load'` can wait forever because a same-document hash change
// never fires a load event.  Assign `location.hash` and wait for the route's
// own DOM instead.
async function gotoHash(page, appUrl, route, readySelector, timeout = 30000) {
  await page.evaluate((hash) => { window.location.hash = hash; }, `#/${route}`);
  await page.locator(readySelector).first().waitFor({ state: 'visible', timeout });
}

// Section markers make a spec timeout diagnosable from the log alone: the
// last printed marker is the step that never completed.
function mark(label) {
  console.log(`[e2e-22 ${new Date().toISOString()}] ${label}`);
}

async function createWebDavTarget(page, baseUrl) {
  await page.getByRole('button', { name: '新建目标' }).first().click();
  const editor = page.locator('.hc-backup-target-editor').first();
  await editor.waitFor({ state: 'visible', timeout: 15000 });
  const urlInput = editor.locator('input[aria-label="服务地址 *"]').first();
  await urlInput.fill(baseUrl);
  const defaultCheck = editor.locator('input[type="checkbox"][aria-label="设为默认目标"]');
  // Playwright `.check()` asserts the post-click state inside its own retry
  // window; the React commit lands a frame later and flakes the assertion.
  // Click then assert the checked property explicitly instead.
  if (await defaultCheck.count()) {
    await defaultCheck.click();
    await expect(defaultCheck).toBeChecked({ timeout: 10000 });
  }
  await editor.getByRole('button', { name: '保存目标' }).first().click();
  const savedMessage = page.locator('.hc-backup-message',
    { hasText: '备份目标已保存' }).first();
  await savedMessage.waitFor({ state: 'visible', timeout: 60000 });
  const savedText = await savedMessage.innerText();
  // Explicitly select the new WebDAV target: :ui :target-id may already hold
  // the implicit :local default, and `backup/create` uses that selection, not
  // the server-side default.
  const webdavRadio = page.locator('.hc-backup-target-choice', { hasText: 'WebDAV' })
    .locator('input[type="radio"]').first();
  await webdavRadio.waitFor({ state: 'visible', timeout: 15000 });
  if (!(await webdavRadio.isChecked())) {
    await webdavRadio.click();
  }
  await expect(webdavRadio).toBeChecked({ timeout: 10000 });
  return savedText;
}

async function createBackup(page) {
  await page.locator('.hc-backup-tab', { hasText: '创建备份' }).first().click();
  const pwInputs = page.locator('.hc-backup .hc-backup-password input[type="password"]');
  await pwInputs.nth(0).waitFor({ state: 'visible', timeout: 15000 });
  await pwInputs.nth(0).fill(BACKUP_PASSWORD);
  await pwInputs.nth(1).fill(BACKUP_PASSWORD);
  const deletedBox = page.locator('.hc-backup-check input[type="checkbox"]')
    .filter({ has: page.locator('xpath=..') })
    .first();
  // The deleted-session checkbox sits next to its label text.
  const box = page.locator('label.hc-backup-check', { hasText: '包含已删除的会话' })
    .locator('input[type="checkbox"]').first();
  await box.click();
  await expect(box).toBeChecked({ timeout: 10000 });
  const createBtn = page.getByRole('button', { name: '生成加密备份' });
  await page.waitForFunction(() => {
    const btn = [...document.querySelectorAll('button')]
      .find((b) => (b.textContent || '').includes('生成加密备份'));
    return btn && !btn.disabled;
  }, null, { timeout: 120000 });
  await createBtn.click();
  await page.locator('.hc-backup-result').waitFor({ state: 'visible', timeout: 180000 });
  await page.waitForFunction(() => {
    const el = document.querySelector('.hc-backup-result');
    return el && /备份 ID/.test(el.textContent || '');
  }, null, { timeout: 120000 });
  const resultText = await page.locator('.hc-backup-result').innerText();
  const idMatch = resultText.match(/备份 ID\s*\n?\s*([0-9a-f-]{36})/i)
    || resultText.match(/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i);
  return { resultText: resultText.slice(0, 2000), backupId: idMatch && idMatch[1] };
}

test('@e2e E2E 22 cross-device migration via WebDAV backup', async ({ browser }) => {
  test.setTimeout(1200000);
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const startedAt = new Date().toISOString();
  const checks = {};
  const diagnostics = {};
  const evidence = {
    test: 'E2E 22', mode: 'automated', mock: true, startedAt,
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    freeze: { bundleSha256: '45f11529a22da07d23e665c412ad6b1611758d63e28bd464727efa32e174fd30',
              treeSha256Prefix: '76cf2fd0' },
    checks, diagnostics, notes: [],
  };
  // final25 freeze pins bundle 45f11529 + tree 76cf2fd0.  W245/W251 landed
  // src fixes after the freeze, so the measured sourceTreeSha256 below is
  // expected to differ from the frozen prefix; that drift is recorded, not
  // hidden.
  evidence.notes.push(
    'freeze tree 76cf2fd0 predates W245/W251 src fixes; '
    + 'measured sourceTreeSha256 reflects the drifted tree intentionally.');
  const writeEvidence = () =>
    writeJson(EVIDENCE_DIR, 'e2e-22', evidence);
  // Observations live in `diagnostics`, never in `checks`.  Each one is
  // registered here; the strict verdict consumes every check via checksOk.
  // `mcpEnabledChecked` is a raw observation whose expected value is false;
  // the consumed verdict is `mcpDisabled`.
  const diagnosticOnly = [
    'seedSource', 'backupCreate', 'webdavRequests', 'webdavFiles',
    'webdavMirror', 'seedDestination', 'restore', 'verify', 'backupSection',
    'sessionListText', 'destinationActiveRows', 'deletedArea',
    'memoryBodyHead', 'memoryAllBodyHead', 'mcpCardText',
    'mcpEnabledChecked', 'mcpStateLabel', 'boardText', 'boardSwarmRows',
    'topbarBadge', 'destinationBackupPane', 'destinationTargetRows',
    'finishedAt',
  ];
  let finalized = false;
  const finalizeEvidence = () => {
    diagnostics.finishedAt = new Date().toISOString();
    const summary = summarizeChecks(checks, diagnostics, diagnosticOnly);
    evidence.checks = summary.checks;
    evidence.diagnostics = summary.diagnostics;
    evidence.diagnosticOnly = summary.diagnosticOnly;
    evidence.failedChecks = summary.failedChecks;
    evidence.ok = summary.ok;
    finalized = true;
    writeEvidence();
    return evidence.ok;
  };
  const abortEvidence = (error) => {
    if (finalized) return;
    evidence.error = String(error && error.message || error).slice(0, 2000);
    evidence.failedChecks = ['run-aborted'];
    evidence.diagnosticOnly = diagnosticOnly;
    evidence.diagnostics = diagnostics;
    diagnostics.finishedAt = new Date().toISOString();
    evidence.ok = false;
    finalized = true;
    writeEvidence();
  };

  const webdav = await startMockWebDAV();
  const rootA = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e22-src-'));
  const dataA = path.join(rootA, 'data');
  const wsA = path.join(rootA, 'workspace');
  const blobA = path.join(rootA, 'blobs');
  fs.mkdirSync(dataA, { recursive: true });
  fs.mkdirSync(wsA, { recursive: true });
  fs.mkdirSync(blobA, { recursive: true });

  let appA;
  let ctxA;
  let pageA;
  try {
    const seedA = runSeed(dataA, blobA, path.join(dataA, 'extensions'), 'source');
    diagnostics.seedSource = seedA;
    checks.seedSourceOk = seedA.ok === true;
    assertCheck(checks.seedSourceOk,
      `source seed failed: ${JSON.stringify(seedA)}`);
    mark('source: startApp');
    appA = await startApp({ root: rootA, port: 0, evidenceDir: EVIDENCE_DIR });
    ctxA = await browser.newContext();
    pageA = await ctxA.newPage();
    mark('source: goto /settings');
    await goto(pageA, appA.url, '/settings');
    mark('source: waitForSync');
    await waitForSync(pageA);
    mark('source: openDataCenter');
    await openDataCenter(pageA);
    mark('source: createWebDavTarget');
    const targetSaveMessage = await createWebDavTarget(pageA, `${webdav.url}/backups`);
    checks.targetSaved = /备份目标已保存/.test(targetSaveMessage);
    assertCheck(checks.targetSaved,
      `target save was not confirmed: ${targetSaveMessage.slice(0, 300)}`);
    await shot(pageA, EVIDENCE_DIR, '01-target-saved');

    mark('source: createBackup');
    const created = await createBackup(pageA);
    diagnostics.backupCreate = {
      backupId: created.backupId,
      resultHead: created.resultText.slice(0, 800),
    };
    checks.backupIdFound = Boolean(created.backupId);
    assertCheck(checks.backupIdFound,
      `no backup id in result: ${created.resultText}`);
    await shot(pageA, EVIDENCE_DIR, '02-backup-created');

    // Mirror the mock WebDAV objects into a file target layout.
    const backupId = created.backupId;
    // Keep the mirror outside rootA: appA.close() removes the whole app
    // tempRoot, and the offline CLI still needs the mirrored objects.
    const mirrorRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e22-mirror-'));
    const objectsDir = path.join(mirrorRoot, 'objects');
    fs.mkdirSync(objectsDir, { recursive: true });
    // The server PUTs `<base-url>/<id>.hcb`; the recorded key may sit under
    // the target collection or any other path segment the target config
    // produced, so match on the id + `.hcb` suffix instead of the exact key.
    const objectEntries = [...webdav.state.files.entries()]
      .filter(([key]) => key.endsWith(`/${backupId}.hcb`) || key.endsWith(`${backupId}.hcb`));
    const hcbEntry = objectEntries.find(([key]) => key.endsWith(`/${backupId}.hcb`))
      || objectEntries[0];
    diagnostics.webdavRequests = webdav.state.requests
      .filter((r) => r.method === 'PUT' || r.method === 'PROPFIND' || r.method === 'GET')
      .slice(-40);
    diagnostics.webdavFiles = [...webdav.state.files.keys()];
    checks.backupObjectFound = Boolean(hcbEntry);
    assertCheck(checks.backupObjectFound,
      `mock WebDAV has no object for backup ${backupId}; files: ${[...webdav.state.files.keys()].join(', ')}; requests: ${JSON.stringify(diagnostics.webdavRequests)}`);
    const [hcbKey, objectBytes] = hcbEntry;
    fs.writeFileSync(path.join(objectsDir, `${backupId}.hcb`), objectBytes);
    diagnostics.webdavMirror = {
      key: hcbKey,
      bytes: objectBytes.length,
      mirrorRoot,
      requests: webdav.state.requests.length,
    };
    checks.mirrorObjectMounted = Number(diagnostics.webdavMirror.bytes) > 0;
    assertCheck(checks.mirrorObjectMounted,
      `mirrored WebDAV object is empty: ${JSON.stringify(diagnostics.webdavMirror)}`);
    await shot(pageA, EVIDENCE_DIR, '03-before-close');
  } catch (error) {
    abortEvidence(error);
    throw error;
  } finally {
    if (pageA) await pageA.close().catch(() => {});
    if (ctxA) await ctxA.close().catch(() => {});
    if (appA) await appA.close().catch(() => {});
  }

  // ---- Offline restore into the destination root ------------------------
  const rootD = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e22-dst-'));
  const dataD = path.join(rootD, 'data');
  const wsD = path.join(rootD, 'workspace');
  const blobD = path.join(rootD, 'blobs');
  fs.mkdirSync(dataD, { recursive: true });
  fs.mkdirSync(wsD, { recursive: true });
  fs.mkdirSync(blobD, { recursive: true });
  try {
    const seedD = runSeed(dataD, blobD, null, 'destination');
    diagnostics.seedDestination = seedD;
    checks.seedDestinationOk = seedD.ok === true;
    assertCheck(checks.seedDestinationOk,
      `destination seed failed: ${JSON.stringify(seedD)}`);
    const mirrorRoot = diagnostics.webdavMirror?.mirrorRoot;
    const backupId = diagnostics.backupCreate.backupId;
    mark('offline: backup cli restore');
    // The offline CLI restores blob objects under --blob-root; stage them
    // outside the destination root, then copy into the blob root the app
    // actually reads (<root>/blobs, not <data-dir>/blobs).
    const stageBlobs = path.join(rootD, 'restored-blobs');
    const restoreOut = runBackupCli([
      'restore',
      '--db-path', path.join(dataD, 'db'),
      '--data-dir', dataD,
      '--target-root', mirrorRoot,
      '--blob-root', stageBlobs,
      '--backup-id', backupId,
      '--mode', 'merge',
      '--include-deleted', 'true',
      '--password-env', 'HC_BACKUP_PASSWORD',
      '--no-prior-app-commands', 'true',
    ], { HC_BACKUP_PASSWORD: BACKUP_PASSWORD });
    const restoreResult = normalizeKeys(parseEdn(restoreOut.trim().split('\n').pop()));
    diagnostics.restore = {
      ok: restoreResult.ok,
      status: restoreResult.receipt?.status,
      error: restoreResult.error || null,
    };
    checks.restoreOk = restoreResult.ok === true;
    assertCheck(checks.restoreOk,
      `restore failed: ${restoreOut.slice(-2000)}`);

    if (fs.existsSync(stageBlobs)) {
      fs.cpSync(stageBlobs, blobD, { recursive: true });
    }

    const verifyD = runVerify(path.join(dataD, 'db'), path.join(dataD, 'extensions'));
    diagnostics.verify = verifyD;

    const byId = Object.fromEntries(verifyD.sessions.map((s) => [s.sessionId, s]));
    const migrated = byId['s-migrated'];
    const deleted = byId['s-deleted'];
    const local = byId['s-local'];
    assertCheck(verifyD.sessions.length === 3,
      `expected 3 sessions after merge, got ${verifyD.sessions.length}: ${JSON.stringify(verifyD.sessions.map((s) => s.sessionId))}`);
    assertCheck(migrated && local && deleted, 'missing migrated/deleted/local session');
    assertCheck(migrated.status === 'active' && migrated.pinned === true,
      `s-migrated wrong: ${JSON.stringify(migrated)}`);
    assertCheck(deleted.status === 'deleted' && deleted.deletedAt,
      `s-deleted missing deleted-at: ${JSON.stringify(deleted)}`);
    assertCheck(migrated.draftIds.includes('d1')
      && (migrated.draftContents.d1 || '').includes('迁移草稿'),
    `drafts not preserved: ${JSON.stringify(migrated.draftIds)}`);
    assertCheck(migrated.modelBinding && migrated.modelBinding.connectionId === 'conn-old'
      && migrated.modelBinding.modelEntryId === 'model-old-1',
    `model-binding not preserved: ${JSON.stringify(migrated.modelBinding)}`);
    const memById = Object.fromEntries(verifyD.memories.map((m) => [m.id, m]));
    assertCheck(memById['mem-archived']?.status === 'archived',
      `archived memory wrong: ${JSON.stringify(memById['mem-archived'])}`);
    assertCheck(memById['mem-local']?.status === 'active',
      `local memory wrong: ${JSON.stringify(memById['mem-local'])}`);
    const mcpFs = verifyD.mcp?.mcpFilesystem;
    assertCheck(mcpFs && mcpFs.enabled === false,
      `MCP server not disabled: ${JSON.stringify(verifyD.mcp)}`);
    assertCheck(mcpFs.tools?.readFile?.enabled === false
      && mcpFs.tools?.readFile?.reviewRequired === true,
    `MCP tool state wrong: ${JSON.stringify(mcpFs?.tools)}`);
    const backupSection = verifyD.backup?.backup;
    const backupTargets = backupSection?.targets;
    diagnostics.backupSection = backupSection || null;
    assertCheck(!backupTargets || Object.keys(backupTargets).length === 0,
      `backup targets restored to destination: ${JSON.stringify(backupSection)}`);
    // Acceptance conditions hidden inside the verify JSON: each one is a
    // consumed boolean, not a diagnostic.
    checks.verifySessionCount = verifyD.sessions.length === 3;
    checks.verifySessionsPresent = Boolean(migrated && local && deleted);
    checks.verifyMigratedActivePinned =
      migrated?.status === 'active' && migrated?.pinned === true;
    checks.verifyDeletedSoft =
      deleted?.status === 'deleted' && Boolean(deleted?.deletedAt);
    checks.verifyDraftPreserved =
      Boolean(migrated?.draftIds?.includes('d1'))
      && Boolean((migrated?.draftContents?.d1 || '').includes('迁移草稿'));
    checks.verifyModelBinding =
      migrated?.modelBinding?.connectionId === 'conn-old'
      && migrated?.modelBinding?.modelEntryId === 'model-old-1';
    checks.verifyArchivedMemory =
      memById['mem-archived']?.status === 'archived';
    checks.verifyLocalMemory = memById['mem-local']?.status === 'active';
    checks.verifyMcpDisabled = Boolean(mcpFs) && mcpFs.enabled === false;
    checks.verifyMcpToolDisabled =
      mcpFs?.tools?.readFile?.enabled === false
      && mcpFs?.tools?.readFile?.reviewRequired === true;
    checks.noRestoredBackupTargets =
      !backupTargets || Object.keys(backupTargets).length === 0;
    checks.verifyOk = verifyD.ok === true;
    assertCheck(checks.verifySessionCount && checks.verifySessionsPresent,
      `verify sessions wrong: ${JSON.stringify(verifyD.sessions)}`);
    assertCheck(checks.verifyMigratedActivePinned && checks.verifyDeletedSoft,
      `verify session states wrong: ${JSON.stringify(byId)}`);
    assertCheck(checks.verifyDraftPreserved && checks.verifyModelBinding,
      `verify drafts/binding wrong: ${JSON.stringify(migrated)}`);
    assertCheck(checks.verifyArchivedMemory && checks.verifyLocalMemory,
      `verify memories wrong: ${JSON.stringify(verifyD.memories)}`);
    assertCheck(checks.verifyMcpDisabled && checks.verifyMcpToolDisabled,
      `verify MCP state wrong: ${JSON.stringify(verifyD.mcp)}`);
    assertCheck(checks.noRestoredBackupTargets,
      `backup targets restored to destination: ${JSON.stringify(backupSection)}`);
    assertCheck(checks.verifyOk,
      `verify script did not report ok: ${JSON.stringify(verifyD).slice(0, 500)}`);
  } catch (error) {
    abortEvidence(error);
    throw error;
  }

  // ---- Destination app: UI verification ---------------------------------
  let appD;
  let ctxD;
  let pageD;
  try {
    mark('destination: startApp');
    appD = await startApp({ root: rootD, port: 0, evidenceDir: EVIDENCE_DIR });
    ctxD = await browser.newContext();
    pageD = await ctxD.newPage();
    evidence.uiErrors = [];
    pageD.on('pageerror', (err) => {
      evidence.uiErrors.push(`pageerror: ${String(err).slice(0, 500)}`);
    });
    pageD.on('console', (msg) => {
      if (msg.type() === 'error') {
        evidence.uiErrors.push(`console.error: ${msg.text().slice(0, 300)}`);
      }
    });
    mark('destination: goto /');
    await goto(pageD, appD.url, '/');
    mark('destination: waitForSync');
    await waitForSync(pageD);

    // Sessions: migrated + local visible; deleted session in the deleted area.
    mark('destination: session list check');
    const listText = await pageD.locator('.wb-session-list').innerText();
    diagnostics.sessionListText = listText.slice(0, 1500);
    const activeRows = await pageD.locator('.wb-session-list .wb-list-button')
      .allInnerTexts();
    diagnostics.destinationActiveRows = activeRows;
    checks.destinationSessionsLoaded =
      listText.includes('E2E22 迁移会话') && listText.includes('E2E22 本地会话');
    checks.deletedSessionHidden =
      !activeRows.some((row) => row.includes('E2E22 已删除会话'));
    assertCheck(checks.destinationSessionsLoaded,
      `migrated/local session missing from list: ${listText.slice(0, 400)}`);
    assertCheck(checks.deletedSessionHidden,
      `deleted session leaked into the active list: ${JSON.stringify(activeRows)}`);
    const deletedDetails = pageD.locator('details.wb-deleted-sessions').first();
    await deletedDetails.locator('summary').first().click();
    await deletedDetails.getByRole('button', { name: /加载已删除会话|刷新已删除会话/ })
      .first().click();
    mark('destination: wait deleted area');
    await pageD.waitForFunction(() => {
      const el = document.querySelector('details.wb-deleted-sessions');
      return el && /E2E22 已删除会话/.test(el.textContent || '');
    }, null, { timeout: 60000 });
    const deletedText = await deletedDetails.innerText();
    diagnostics.deletedArea = deletedText.slice(0, 1200);
    checks.deletedSessionInDeletedArea =
      /已删除会话（1）/.test(deletedText)
      || deletedText.includes('E2E22 已删除会话');
    assertCheck(checks.deletedSessionInDeletedArea,
      `deleted area wrong: ${deletedText.slice(0, 400)}`);
    await shot(pageD, EVIDENCE_DIR, '04-destination-sessions');

    // Memory page: archived memory stays archived, local memory survives.
    mark('destination: goto /materials');
    await gotoHash(pageD, appD.url, 'materials', '.wb-tabs [role="tab"]');
    mark('destination: materials tabs visible');
    // The materials page opens on the files tab; switch to 长期记忆 and the
    // archived filter so both memories are listed.
    await pageD.getByRole('tab', { name: '长期记忆', exact: true }).first().click();
    await pageD.locator('.hc-mat-filters').first()
      .waitFor({ state: 'visible', timeout: 30000 });
    const archivedFilter = pageD.locator('.hc-mat-filters button', { hasText: '已归档' }).first();
    if (await archivedFilter.count()) await archivedFilter.click();
    // Wait for the archived memory body instead of a fixed settle time.
    await pageD.waitForFunction(() =>
      document.body.innerText.includes('用户偏好简洁回答'),
    null, { timeout: 30000 });
    const memBody = await pageD.locator('body').innerText();
    diagnostics.memoryBodyHead = memBody.slice(0, 4000);
    checks.archivedMemoryVisible = memBody.includes('用户偏好简洁回答');
    assertCheck(checks.archivedMemoryVisible,
      'archived memory body missing');
    const allFilter = pageD.locator('.hc-mat-filters button', { hasText: '全部' }).first();
    let memoryAllBody = memBody;
    if (await allFilter.count()) {
      await allFilter.click();
      await pageD.waitForFunction(() =>
        document.body.innerText.includes('本地记忆'),
      null, { timeout: 30000 });
      memoryAllBody = await pageD.locator('body').innerText();
    }
    diagnostics.memoryAllBodyHead = memoryAllBody.slice(0, 4000);
    checks.localMemoryVisible = memoryAllBody.includes('本地记忆');
    assertCheck(checks.localMemoryVisible,
      'local memory body missing');
    await shot(pageD, EVIDENCE_DIR, '05-destination-memory');
    mark('destination: memory shot done');

    // MCP catalog: the disabled server must not appear enabled anywhere.
    // The MCP panel mounts only under the 扩展 tab; sampling the memory tab
    // never contained it, so the old check was a false negative.
    const mcpCardName = '旧设备文件系统 MCP';
    checks.mcpSurface = false;
    checks.mcpCardNamed = false;
    checks.mcpDisabled = false;
    checks.mcpNotConnected = false;
    diagnostics.mcpCardText = '';
    diagnostics.mcpEnabledChecked = null;
    diagnostics.mcpStateLabel = '';
    await pageD.getByRole('tab', { name: '扩展', exact: true }).first()
      .click({ timeout: 10000 });
    const extPanel = pageD.locator('.hc-ext-panel').first();
    await extPanel.waitFor({ state: 'visible', timeout: 30000 });
    // Bounded wait for the restored card; the toolbar label is
    // `读取最新状态` (hc.client.extensions.view/toolbar).  If the card is not
    // there yet, click that refresh once with an explicit timeout.
    const waitForCard = () => pageD.waitForFunction((name) => {
      return [...document.querySelectorAll('.hc-ext-card')]
        .some((el) => (el.textContent || '').includes(name));
    }, mcpCardName, { timeout: 15000 }).then(() => true).catch(() => false);
    let cardReady = await waitForCard();
    if (!cardReady) {
      await pageD.getByRole('button', { name: '读取最新状态', exact: true })
        .first().click({ timeout: 10000 }).catch(() => {});
      cardReady = await waitForCard();
    }
    // One atomic DOM read: card existence, text, checkbox state and badge
    // label.  No Playwright locator may wait for visibility after this point.
    const cardData = await pageD.evaluate((name) => {
      const card = [...document.querySelectorAll('.hc-ext-card')]
        .find((el) => (el.textContent || '').includes(name));
      if (!card) return null;
      const checkbox = card.querySelector(
        '.hc-ext-card-actions input[type="checkbox"]');
      const badge = card.querySelector('.hc-ext-card-actions .hc-badge');
      return {
        text: (card.innerText || '').trim(),
        enabledChecked: checkbox ? checkbox.checked : null,
        stateLabel: (badge?.textContent || '').trim(),
      };
    }, mcpCardName);
    checks.mcpSurface = Boolean(cardData);
    if (cardData) {
      diagnostics.mcpCardText = cardData.text.slice(0, 1200);
      diagnostics.mcpEnabledChecked = cardData.enabledChecked;
      diagnostics.mcpStateLabel = cardData.stateLabel;
      checks.mcpCardNamed = cardData.text.includes(mcpCardName);
      checks.mcpDisabled = cardData.enabledChecked === false;
      checks.mcpNotConnected = cardData.stateLabel !== '已连接'
        && cardData.stateLabel !== '可用';
    }
    if (!checks.mcpSurface) {
      diagnostics.mcpCardMissing = true;
      throw new Error(
        `MCP card "${mcpCardName}" did not appear within 15s after refresh`);
    }
    assertCheck(checks.mcpSurface,
      `MCP panel card missing: ${diagnostics.mcpCardText.slice(0, 300)}`);
    assertCheck(checks.mcpCardNamed,
      `MCP card does not name the restored server: ${diagnostics.mcpCardText.slice(0, 500)}`);
    assertCheck(checks.mcpDisabled,
      `MCP server rendered enabled: ${diagnostics.mcpCardText.slice(0, 500)}`);
    assertCheck(checks.mcpNotConnected,
      `MCP server rendered connected: ${diagnostics.mcpStateLabel}`);
    await shot(pageD, EVIDENCE_DIR, '06-destination-materials-ext');

    // Board: no fleet/sync badge content (empty board).
    mark('destination: goto /board');
    await gotoHash(pageD, appD.url, 'board', '.wb-content');
    mark('destination: board checks');
    await pageD.waitForFunction(() =>
      /Swarm 总览|尚未准入任何 swarm/.test(document.body.innerText || ''),
    null, { timeout: 30000 });
    const boardText = await pageD.locator('body').innerText();
    diagnostics.boardText = boardText.slice(0, 1200);
    // The board chrome and its empty state mention "swarm"/"fleet" by name;
    // what must not appear is real fleet data.  The dashboard renders one
    // `.hc-swarm-row` per admitted swarm, so an empty board has none.
    const swarmRows = await pageD.locator('.hc-swarm-row').count();
    diagnostics.boardSwarmRows = swarmRows;
    checks.boardEmpty = swarmRows === 0;
    assertCheck(checks.boardEmpty,
      `unexpected fleet rows on board: ${swarmRows}`);
    const badgeText = await pageD.locator('.wb-badge').first().innerText().catch(() => '');
    diagnostics.topbarBadge = badgeText;
    checks.topbarSynced = badgeText.includes('已同步');
    assertCheck(checks.topbarSynced,
      `topbar badge not synced: ${badgeText}`);
    await shot(pageD, EVIDENCE_DIR, '07-destination-board');

    // Settings data center: no restored WebDAV backup target.
    mark('destination: goto /settings');
    await gotoHash(pageD, appD.url, 'settings', '.hc-settings, .wb-content');
    mark('destination: waitForSync on settings');
    await waitForSync(pageD);
    await openDataCenter(pageD);
    mark('destination: openDataCenter done');
    await pageD.locator('.hc-backup-target, .hc-backup').first()
      .waitFor({ state: 'visible', timeout: 30000 });
    await pageD.waitForFunction(() => {
      const el = document.querySelector('.hc-backup');
      return Boolean(el && /备份目标|备份目录|最近成功备份|尚无成功备份/
        .test(el.innerText || ''));
    }, null, { timeout: 30000 });
    const backupPane = await pageD.locator('.hc-backup').first().innerText();
    diagnostics.destinationBackupPane = backupPane.slice(0, 3000);
    // The pane's help text names WebDAV/S3 as supported kinds; what must not
    // leak is an actual remote target row.  Each configured target renders
    // as one `.hc-backup-target-choice` whose strong starts with the kind
    // label, so inspect those rows instead of the whole pane text.
    const targetRows = await pageD.locator('.hc-backup-target-choice strong')
      .allInnerTexts();
    diagnostics.destinationTargetRows = targetRows;
    checks.noRemoteBackupTarget =
      !targetRows.some((row) => /WebDAV|S3/.test(row));
    checks.noMockAddressLeak =
      !targetRows.some((row) => row.includes('127.0.0.1'));
    assertCheck(checks.noRemoteBackupTarget,
      `remote backup target leaked to destination: ${JSON.stringify(targetRows)}`);
    assertCheck(checks.noMockAddressLeak,
      `mock WebDAV address leaked to destination: ${JSON.stringify(targetRows)}`);
    await shot(pageD, EVIDENCE_DIR, '08-destination-no-target');
  } catch (error) {
    abortEvidence(error);
    throw error;
  } finally {
    if (pageD) await pageD.close().catch(() => {});
    if (ctxD) await ctxD.close().catch(() => {});
    if (appD) await appD.close().catch(() => {});
    await webdav.close().catch(() => {});
  }

  const finalOk = finalizeEvidence();
  assertCheck(finalOk,
    `one or more E2E 22 checks failed: ${(evidence.failedChecks || []).join(', ')}`);
});
