// E2E 16: scheduled tasks — device vs fixed timezone, missed occurrences,
// manual run, overlap skip, restore default-pause, restricted platform.
//
// Time control: the app's scheduler clock is the wall clock; the E2E `:clock`
// config section is not wired into `hc.server.app/system` (scheduler/start!
// receives no :clock).  Missed and overlap facts are therefore produced by
// seeding the `scheduler` storage partition (test/e2e/e2e16_seed.clj) with
// next-run values ~25h in the past plus a live `:running` run, then letting
// the real recovery path (`hc.compose.scheduler.core/recover`,
// process-restart) classify them.  Recovery scans real daily 08:30
// occurrences inside [next-run, now]; offsets of only 1-2h silently produce
// an empty window whenever the test runs after ~09:30 local, so the seed
// must backdate far enough that a daily occurrence always falls inside the
// window.  That is the same durable shape a real app restart produces.
//
// Evidence: plans/evidence/e2e-16/ (JSON + screenshots + bundle/tree hashes).
import { test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { startApp, REPO_ROOT, clojureCommand } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert as assertCheck, goto, shot, waitForSync, writeJson,
  clientBundleInfo,
} from './support/ui.mjs';
import { checksOk, sourceTreeSha256 } from './support/evidence.mjs';

const EVIDENCE_DIR = path.join(REPO_ROOT, 'plans/evidence/e2e-16');
const BACKUP_PASSWORD = 'e2e16 backup passphrase';
const IOS_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) '
  + 'AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 '
  + 'Safari/604.1';

function runSeed(dataDir, scenario, nowMs) {
  const out = execFileSync(clojureCommand(),
    ['-M:server', '-i', 'test/e2e/e2e16_seed.clj',
     '--eval', `(e2e16-seed/run ${JSON.stringify(dataDir)} ${JSON.stringify(scenario)} ${JSON.stringify(nowMs)})`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180000 });
  return JSON.parse(out.trim().split('\n').pop());
}

function runVerify(dbPath) {
  const out = execFileSync(clojureCommand(),
    ['-M:server', '-i', 'test/e2e/e2e16_verify.clj',
     '--eval', `(e2e16-verify/run ${JSON.stringify(dbPath)})`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180000 });
  return JSON.parse(out.trim().split('\n').pop());
}

function runBackupCli(args, env = {}) {
  try {
    return execFileSync(clojureCommand(),
      ['-M:server', '-m', 'hc.server.backup.cli', ...args],
      { cwd: REPO_ROOT, encoding: 'utf8', timeout: 300000,
        env: { ...process.env, ...env } });
  } catch (error) {
    throw new Error(
      `backup cli failed: ${args.join(' ')}\n` +
      `status=${error.status} signal=${error.signal}\n` +
      `stdout=${(error.stdout || '').slice(-2000)}\n` +
      `stderr=${(error.stderr || '').slice(-2000)}`);
  }
}

// hc.server.backup.cli prints one EDN map via pr-str, not JSON (same shape
// as e2e-22).  Minimal reader for the receipt shapes this test needs:
// maps, vectors, strings, keywords, numbers, booleans, nil.
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
  const normalize = (v) => {
    if (Array.isArray(v)) return v.map(normalize);
    if (v && typeof v === 'object') {
      return Object.fromEntries(Object.entries(v).map(([k, val]) => [
        typeof k === 'string' && k.startsWith(':') ? k.slice(1).replace(/\?$/, '') : k,
        normalize(val),
      ]));
    }
    return v;
  };
  return normalize(value);
}

async function openScheduleTab(page) {
  // At <=1023px the nav drawer is hidden until the hamburger opens it.
  const menu = page.locator('#wb-menu');
  if (await menu.isVisible().catch(() => false)) {
    await menu.click({ timeout: 15000 });
    await page.locator('.wb-nav[data-open="true"]')
      .first().waitFor({ state: 'visible', timeout: 10000 });
  }
  await page.getByRole('button', { name: '设置与数据' }).first().click();
  await page.getByRole('tab', { name: '定时任务' }).first().click();
  await page.locator('.hc-schedule').first().waitFor({ state: 'visible', timeout: 30000 });
}

async function scheduleCard(page, name) {
  const card = page.locator('.hc-schedule-card', { hasText: name }).first();
  await card.waitFor({ state: 'visible', timeout: 30000 });
  return card;
}

async function cardText(card) {
  return (await card.innerText()) || '';
}

async function createPausedSchedule(page, { name, fixedZone = null }) {
  await page.getByRole('button', { name: '新建任务' }).first().click();
  const form = page.locator('.hc-schedule-form').first();
  await form.waitFor({ state: 'visible', timeout: 15000 });
  const ACT = { timeout: 15000 };
  // Scope every field to its labelled .hc-schedule-field so select indexes
  // and input order can never drift when the form gains fields.
  const field = (label) => form.locator('label.hc-schedule-field',
    { has: page.locator('.hc-schedule-field__label',
                        { hasText: new RegExp(`^${label}$`) }) });
  await field('名称').locator('input').first().fill(name, ACT);
  await field('提示词').locator('textarea').first()
    .fill(`E2E16 任务正文 ${name}`, ACT);
  if (fixedZone) {
    await field('时区').locator('select').first()
      .selectOption('fixed', ACT); // 时区 mode
    await field('IANA 时区名称').locator('input').first().fill(fixedZone, ACT);
  }
  // 默认保存为暂停（desktop 也可选 enable；这里显式选 paused）。
  await form.locator('input[type="radio"][value="paused"]').first().check(ACT);
  await form.locator('button[type="submit"]').first().click(ACT);
  await page.locator('.hc-schedule-notice', { hasText: '已保存为暂停' })
    .first().waitFor({ state: 'visible', timeout: 30000 });
}

async function manualRun(page, name) {
  const card = await scheduleCard(page, name);
  await card.getByRole('button', { name: '立即运行' }).first()
    .click({ timeout: 15000 });
  await page.locator('.hc-schedule-panel--manual').first()
    .waitFor({ state: 'visible', timeout: 15000 });
  await page.getByRole('button', { name: '确认立即运行' }).first()
    .click({ timeout: 15000 });
}

test('@e2e E2E 16 schedules timezone missed overlap manual import platform', async ({ browser }) => {
  test.setTimeout(900000);
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const startedAt = new Date().toISOString();
  const checks = {};
  const diagnostics = {};
  const diagnosticOnly = [
    'manualRun', 'timezone', 'ios', 'recover', 'resume',
    'backup', 'restore', 'importedSchedule',
  ];
  const evidence = {
    test: 'E2E 16', mode: 'automated', mock: true, startedAt,
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    freeze: { bundleSha256: '45f11529a22da07d23e665c412ad6b1611758d63e28bd464727efa32e174fd30',
              treeSha256Prefix: '76cf2fd0' },
    checks, diagnostics, diagnosticOnly, notes: [],
  };
  const writeEvidence = () =>
    writeJson(EVIDENCE_DIR, 'e2e-16', evidence);

  const model = await startMockModel({ reply: 'E2E16 mock reply', chunkDelayMs: 5 });
  const appOptions = {
    evidenceDir: EVIDENCE_DIR,
    models: {
      connections: [{
        id: 'mock', name: 'E2E16 Mock', baseUrl: `${model.url}/v1`,
        models: [{ id: 'mock-fast', displayName: 'Mock Fast',
                   maxOutputTokens: 2048, capabilities: ['tools'] }],
      }],
      roles: { main: { connection: 'mock', model: 'mock-fast' },
               task: { connection: 'mock', model: 'mock-fast' } },
    },
  };
  try {

  // ---- App A: UI create/preview/timezone/manual-run ---------------------
  const rootA = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e16-a-'));
  const dataA = path.join(rootA, 'data');
  const wsA = path.join(rootA, 'workspace');
  fs.mkdirSync(dataA, { recursive: true });
  fs.mkdirSync(wsA, { recursive: true });
  runSeed(dataA, 'main', Date.now());

  let appA;
  let ctxA;
  let pageA;
  try {
    appA = await startApp({ ...appOptions, root: rootA, port: 0 });
    ctxA = await browser.newContext({ timezoneId: 'Asia/Shanghai' });
    pageA = await ctxA.newPage();
    await goto(pageA, appA.url, '/settings');
    await waitForSync(pageA);
    await openScheduleTab(pageA);
    console.log('E2E16 checkpoint: appA schedule tab open');

    // Seeded paused schedules visible.
    let seededPausedVisible = true;
    for (const name of ['E2E16 跟随设备 08:30', 'E2E16 固定上海 08:30', 'E2E16 手动运行']) {
      const card = await scheduleCard(pageA, name);
      const text = await cardText(card);
      seededPausedVisible = seededPausedVisible
        && text.includes('已暂停')
        && (text.includes('跟随设备时区') || text.includes('固定时区'));
      assertCheck(text.includes('已暂停'), `${name} not paused: ${text}`);
      assertCheck(text.includes('跟随设备时区') || text.includes('固定时区'),
                  `${name} missing timezone label: ${text}`);
    }
    checks.seededPausedVisible = seededPausedVisible;
    assertCheck(checks.seededPausedVisible, 'seeded paused schedules not visible');
    await shot(pageA, EVIDENCE_DIR, '01-seeded-paused');

    // Timezone distinction: create a follow-device schedule and a fixed one
    // through the real form; the preview must show the zone.
    await createPausedSchedule(pageA, { name: 'E2E16 跟随设备 NY 前' });
    await createPausedSchedule(pageA, { name: 'E2E16 固定上海 表单', fixedZone: 'Asia/Shanghai' });
    const createdFollowText = await cardText(await scheduleCard(pageA, 'E2E16 跟随设备 NY 前'));
    const createdFixedText = await cardText(await scheduleCard(pageA, 'E2E16 固定上海 表单'));
    checks.createdViaForm = /已暂停/.test(createdFollowText)
      && /已暂停/.test(createdFixedText)
      && /(跟随设备时区|固定时区)/.test(createdFollowText)
      && /(跟随设备时区|固定时区)/.test(createdFixedText);
    assertCheck(checks.createdViaForm, 'schedules created through the form are not paused/zoned');
    await shot(pageA, EVIDENCE_DIR, '02-created-paused');

    // Manual run on the seeded paused schedule: confirm dialog promises no
    // plan change; afterwards schedule stays paused with same next-run.
    // NOTE: the run is produced against the local mock model stub
    // (mock-fast via startMockModel), not a real model provider.
    console.log('E2E16 checkpoint: before manualRun');
    const beforeManual = await runVerify(path.join(dataA, 'db'));
    const manualNextBefore = beforeManual.schedules['sched-manual']?.nextRun;
    await manualRun(pageA, 'E2E16 手动运行');
    console.log('E2E16 checkpoint: manualRun done');
    await pageA.locator('.hc-schedule-notice', { hasText: '已开始手动运行' })
      .first().waitFor({ state: 'visible', timeout: 60000 });
    const manualAfter = await runVerify(path.join(dataA, 'db'));
    const manualSched = manualAfter.schedules['sched-manual'];
    diagnostics.manualRun = {
      stub: 'mock-model',
      note: 'manual run exercised against local mock model stub, not a real provider',
      state: manualSched?.state,
      nextRun: manualSched?.nextRun,
      runCount: manualAfter.runs.length,
      lastRun: manualAfter.runs.at(-1) || null,
    };
    assertCheck(manualSched?.state === 'paused',
                `manual run changed schedule state: ${JSON.stringify(manualSched)}`);
    assertCheck(manualAfter.runs.some((r) => r.trigger === 'manual'),
                'no manual run recorded');
    checks.manualRunOk = manualSched?.state === 'paused'
      && manualAfter.runs.some((r) => r.trigger === 'manual');
    checks.manualRunKeptPlan = manualSched?.nextRun === manualNextBefore;
    assertCheck(checks.manualRunOk, 'manual run was not recorded or changed the state');
    assertCheck(checks.manualRunKeptPlan,
      `manual run changed the next-run: ${manualNextBefore} -> ${manualSched?.nextRun}`);
    await shot(pageA, EVIDENCE_DIR, '03-manual-run');

    // Device timezone change: new context emulating America/New_York.
    const ctxB = await browser.newContext({ timezoneId: 'America/New_York' });
    const pageB = await ctxB.newPage();
    await goto(pageB, appA.url, '/settings');
    await waitForSync(pageB);
    await openScheduleTab(pageB);
    // The client pushes the browser zone; the server recomputes follow-device
    // schedules and leaves fixed ones alone.
    await pageB.waitForFunction(() => {
      const el = document.querySelector('.hc-schedule');
      return el && /时区已变更为 America\/New_York|设备时区已更新为 America\/New_York/.test(el.textContent || '');
    }, null, { timeout: 60000 });
    const verifyTz = await runVerify(path.join(dataA, 'db'));
    const follow = verifyTz.schedules['sched-follow'];
    const fixed = verifyTz.schedules['sched-fixed'];
    diagnostics.timezone = {
      deviceZone: verifyTz.deviceZone,
      followZone: follow?.timezone?.zone,
      followMode: follow?.timezone?.mode,
      fixedZone: fixed?.timezone?.zone,
      fixedMode: fixed?.timezone?.mode,
      followNextRun: follow?.nextRun,
      fixedNextRun: fixed?.nextRun,
    };
    assertCheck(verifyTz.deviceZone === 'America/New_York',
                `device zone not adopted: ${verifyTz.deviceZone}`);
    assertCheck(follow?.timezone?.mode === 'follow-device'
                && follow?.timezone?.zone === 'America/New_York',
                `follow-device zone wrong: ${JSON.stringify(follow?.timezone)}`);
    assertCheck(fixed?.timezone?.zone === 'Asia/Shanghai',
                `fixed zone moved: ${JSON.stringify(fixed?.timezone)}`);
    assertCheck(follow?.nextRun !== fixed?.nextRun,
                'follow/fixed next-run indistinguishable');
    checks.timezoneDistinguishable = verifyTz.deviceZone === 'America/New_York'
      && follow?.timezone?.mode === 'follow-device'
      && follow?.timezone?.zone === 'America/New_York'
      && fixed?.timezone?.zone === 'Asia/Shanghai'
      && follow?.nextRun !== fixed?.nextRun;
    assertCheck(checks.timezoneDistinguishable, 'follow/fixed timezone results not distinguishable');
    await shot(pageB, EVIDENCE_DIR, '04-timezone-new-york');
    await ctxB.close().catch(() => {});

    // ---- iOS restricted platform (UA simulation) -------------------------
    const ctxIos = await browser.newContext({
      userAgent: IOS_UA,
      viewport: { width: 390, height: 844 },
    });
    const pageIos = await ctxIos.newPage();
    await goto(pageIos, appA.url, '/settings');
    await waitForSync(pageIos);
    await openScheduleTab(pageIos);
    // A fresh context loads the schedule list asynchronously after the outer
    // .hc-schedule section mounts; wait for a card before reading its actions.
    await pageIos.locator('.hc-schedule-card').first()
      .waitFor({ state: 'visible', timeout: 30000 });
    console.log('E2E16 checkpoint: ios schedule tab open');
    const iosText = await pageIos.locator('.hc-schedule').first().innerText();
    checks.iosManualOnly = iosText.includes('仅支持前台手动运行');
    checks.iosOffersManualRun = (await pageIos.getByRole('button', { name: '立即运行' }).count()) > 0;
    assertCheck(checks.iosManualOnly, 'iOS manual-only notice missing');
    assertCheck(checks.iosOffersManualRun, 'iOS restricted platform does not offer foreground manual run');
    await pageIos.getByRole('button', { name: '新建任务' }).first()
      .click({ timeout: 15000 });
    const iosForm = pageIos.locator('.hc-schedule-form').first();
    await iosForm.waitFor({ state: 'visible', timeout: 15000 });
    const iosField = (label) => iosForm.locator('label.hc-schedule-field',
      { has: pageIos.locator('.hc-schedule-field__label',
                             { hasText: new RegExp(`^${label}$`) }) });
    await iosField('名称').locator('input').first()
      .fill('E2E16 iOS 任务', { timeout: 15000 });
    await iosField('提示词').locator('textarea').first()
      .fill('iOS 平台受限验证', { timeout: 15000 });
    const enableRadio = iosForm.locator('input[type="radio"][value="enable"]').first();
    const pausedRadio = iosForm.locator('input[type="radio"][value="paused"]').first();
    checks.iosNoBackgroundEnable = await enableRadio.isDisabled();
    checks.iosPausedChecked = await pausedRadio.isChecked();
    assertCheck(checks.iosNoBackgroundEnable,
                'iOS enable radio is available; a background schedule could be created');
    assertCheck(checks.iosPausedChecked,
                'iOS default save-mode is not paused');
    diagnostics.ios = {
      manualOnlyNotice: checks.iosManualOnly,
      offersManualRun: checks.iosOffersManualRun,
      enableDisabled: checks.iosNoBackgroundEnable,
      pausedChecked: checks.iosPausedChecked,
    };
    console.log('E2E16 checkpoint: ios assertions done');
    await shot(pageIos, EVIDENCE_DIR, '05-ios-restricted');
    await ctxIos.close().catch(() => {});
  } finally {
    if (pageA) await pageA.close().catch(() => {});
    if (ctxA) await ctxA.close().catch(() => {});
    if (appA) await appA.close().catch(() => {});
  }

  // ---- App B: recovery — missed, overlap skip, resume --------------------
  const rootB = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e16-b-'));
  const dataB = path.join(rootB, 'data');
  const wsB = path.join(rootB, 'workspace');
  fs.mkdirSync(dataB, { recursive: true });
  fs.mkdirSync(wsB, { recursive: true });
  runSeed(dataB, 'recover', Date.now());

  let appB;
  let ctxB2;
  let pageC;
  try {
    appB = await startApp({ ...appOptions, root: rootB, port: 0 });
    ctxB2 = await browser.newContext({ timezoneId: 'Asia/Shanghai' });
    pageC = await ctxB2.newPage();
    await goto(pageC, appB.url, '/settings');
    await waitForSync(pageC);
    await openScheduleTab(pageC);
    console.log('E2E16 checkpoint: appB schedule tab open');

    const verifyB = await runVerify(path.join(dataB, 'db'));
    diagnostics.recover = verifyB;
    const missedRun = verifyB.runs.find((r) => r.scheduleId === 'sched-missed');
    const skippedRun = verifyB.runs.find((r) => r.scheduleId === 'sched-overlap'
                                              && r.status === 'skipped');
    const interruptedRun = verifyB.runs.find((r) => r.id === 'run-overlap');
    const missedSched = verifyB.schedules['sched-missed'];
    const overlapSched = verifyB.schedules['sched-overlap'];
    const resumeSched = verifyB.schedules['sched-resume'];

    assertCheck(missedRun?.status === 'missed'
                && missedRun?.reason === 'process-restart',
                `missed run wrong: ${JSON.stringify(missedRun)}`);
    const missedRuns = verifyB.runs.filter((r) => r.scheduleId === 'sched-missed');
    assertCheck(missedSched?.state === 'enabled'
                && missedSched?.nextRun > Date.now(),
                `missed schedule not advanced: ${JSON.stringify(missedSched)}`);
    checks.missedNotCaughtUp = missedRun?.status === 'missed'
      && missedRun?.reason === 'process-restart'
      && missedSched?.state === 'enabled'
      && missedSched?.nextRun > Date.now();
    checks.missedNoCatchUpRun = !missedRuns.some((r) =>
      r.status === 'running' || r.status === 'completed');
    assertCheck(checks.missedNotCaughtUp, 'missed occurrence was not recorded as missed');
    assertCheck(checks.missedNoCatchUpRun,
      `missed occurrence was caught up: ${JSON.stringify(missedRuns)}`);

    assertCheck(skippedRun?.reason === 'previous-still-running',
                `overlap not skipped: ${JSON.stringify(verifyB.runs)}`);
    assertCheck(interruptedRun?.status === 'interrupted'
                && interruptedRun?.reason === 'process-restart',
                `live run not interrupted: ${JSON.stringify(interruptedRun)}`);
    checks.overlapSkipped = skippedRun?.reason === 'previous-still-running'
      && interruptedRun?.status === 'interrupted'
      && interruptedRun?.reason === 'process-restart';
    checks.overlapNoRunningRun = !verifyB.runs.some((r) =>
      r.scheduleId === 'sched-overlap' && r.status === 'running');
    assertCheck(checks.overlapSkipped, 'overlap was not skipped');
    assertCheck(checks.overlapNoRunningRun, 'overlap left a running occurrence');

    // UI shows 未运行 / 已跳过 labels.
    const missedCard = await scheduleCard(pageC, 'E2E16 错过测试');
    const missedText = await cardText(missedCard);
    assertCheck(/未运行/.test(missedText), `missed label missing: ${missedText}`);
    const overlapCard = await scheduleCard(pageC, 'E2E16 重叠跳过');
    const overlapText = await cardText(overlapCard);
    assertCheck(/已跳过/.test(overlapText) || /上次仍在运行/.test(overlapText),
                `overlap skip label missing: ${overlapText}`);
    await shot(pageC, EVIDENCE_DIR, '06-recover-missed-overlap');

    // Resume paused schedule: sched-resume was seeded paused with a
    // next-run already in the past, so resume must record the elapsed
    // occurrences as :missed facts with reason :paused and schedule the
    // next strictly-future run — never a catch-up :running/:completed.
    const resumeCard = await scheduleCard(pageC, 'E2E16 恢复暂停');
    // Exact name: the card title button 「E2E16 恢复暂停」 also contains
    // 「恢复」 and would win a substring match; clicking it only opens the
    // detail view and never sends schedule/resume.
    await resumeCard.getByRole('button', { name: '恢复', exact: true })
      .click({ timeout: 15000 });
    await pageC.locator('.hc-schedule-notice', { hasText: '已恢复' })
      .first().waitFor({ state: 'visible', timeout: 30000 });
    const verifyB2 = await runVerify(path.join(dataB, 'db'));
    const resumeRuns = verifyB2.runs.filter((r) => r.scheduleId === 'sched-resume');
    diagnostics.resume = {
      state: verifyB2.schedules['sched-resume']?.state,
      nextRun: verifyB2.schedules['sched-resume']?.nextRun,
      runs: resumeRuns.map((r) => ({ status: r.status, reason: r.reason, trigger: r.trigger })),
    };
    assertCheck(verifyB2.schedules['sched-resume']?.state === 'enabled',
                'resume did not enable schedule');
    const pausedMissed = resumeRuns.filter((r) =>
      r.status === 'missed' && r.reason === 'paused');
    assertCheck(pausedMissed.length >= 1,
                `resume recorded no :paused missed facts: ${JSON.stringify(resumeRuns)}`);
    assertCheck(resumeRuns.every((r) => r.status === 'missed'
                                    || r.status === 'skipped'),
                `resume produced a run instead of missed facts: ${JSON.stringify(resumeRuns)}`);
    assertCheck(verifyB2.schedules['sched-resume']?.nextRun > Date.now(),
                `resume next-run not in the future: ${JSON.stringify(verifyB2.schedules['sched-resume'])}`);
    checks.resumeMissedNotRerun = verifyB2.schedules['sched-resume']?.state === 'enabled'
      && pausedMissed.length >= 1
      && resumeRuns.every((r) => r.status === 'missed' || r.status === 'skipped')
      && verifyB2.schedules['sched-resume']?.nextRun > Date.now();
    checks.resumeNoCatchUpRun = !resumeRuns.some((r) =>
      r.status === 'running' || r.status === 'completed');
    assertCheck(checks.resumeMissedNotRerun, 'resume did not record missed facts');
    assertCheck(checks.resumeNoCatchUpRun,
      `resume caught up a run: ${JSON.stringify(resumeRuns)}`);
    await shot(pageC, EVIDENCE_DIR, '07-resume');
  } finally {
    if (pageC) await pageC.close().catch(() => {});
    if (ctxB2) await ctxB2.close().catch(() => {});
    if (appB) await appB.close().catch(() => {});
  }

  console.log('E2E16 checkpoint: appB done, starting backup phase');
  // ---- Backup/restore: imported schedules default to paused -------------
  const rootC = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e16-c-'));
  const dataC = path.join(rootC, 'data');
  const wsC = path.join(rootC, 'workspace');
  const backupRoot = path.join(rootC, 'backups');
  fs.mkdirSync(dataC, { recursive: true });
  fs.mkdirSync(wsC, { recursive: true });
  fs.mkdirSync(backupRoot, { recursive: true });
  runSeed(dataC, 'backup', Date.now());

  let appC;
  try {
    // keepTemp: close() deletes tempRoot when the child already exited,
    // which would remove the seeded store before the offline backup CLI runs.
    appC = await startApp({ ...appOptions, root: rootC, port: 0,
                            keepTemp: true });
    await appC.close();
    appC = null;

    console.log('E2E16 checkpoint: before backup cli');
    const backupOut = runBackupCli([
      'backup', '--db-path', path.join(dataC, 'db'),
      '--data-dir', dataC, '--target-root', backupRoot,
      '--password-env', 'HC_BACKUP_PASSWORD',
    ], { HC_BACKUP_PASSWORD: BACKUP_PASSWORD });
    const backupResult = parseEdn(backupOut.trim().split('\n').pop());
    diagnostics.backup = { ok: backupResult.ok,
                           status: backupResult.receipt?.status,
                           backupId: backupResult.receipt?.id };
    checks.backupSucceeded = backupResult.ok === true;
    assertCheck(checks.backupSucceeded, `backup failed: ${backupOut}`);
    assertCheck(backupResult.ok, `backup failed: ${backupOut}`);
    const backupId = diagnostics.backup.backupId;
    assertCheck(backupId, `no backup id in receipt: ${backupOut}`);

    // Destination store: seed empty scheduler partition first.
    const rootD = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e16-d-'));
    const dataD = path.join(rootD, 'data');
    const wsD = path.join(rootD, 'workspace');
    fs.mkdirSync(dataD, { recursive: true });
    fs.mkdirSync(wsD, { recursive: true });
    runSeed(dataD, 'restore', Date.now());

    console.log('E2E16 checkpoint: before restore cli');
    const restoreOut = runBackupCli([
      'restore', '--db-path', path.join(dataD, 'db'),
      '--data-dir', dataD,
      '--target-root', backupRoot,
      '--backup-id', backupId,
      '--mode', 'merge',
      '--password-env', 'HC_BACKUP_PASSWORD',
      '--no-prior-app-commands', 'true',
    ], { HC_BACKUP_PASSWORD: BACKUP_PASSWORD });
    const restoreResult = parseEdn(restoreOut.trim().split('\n').pop());
    diagnostics.restore = { ok: restoreResult.ok,
                            status: restoreResult.receipt?.status };
    checks.restoreSucceeded = restoreResult.ok === true;
    assertCheck(checks.restoreSucceeded, `restore failed: ${restoreOut}`);
    assertCheck(restoreResult.ok, `restore failed: ${restoreOut}`);

    const verifyD = runVerify(path.join(dataD, 'db'));
    const imported = verifyD.schedules['sched-backup'];
    diagnostics.importedSchedule = imported;
    assertCheck(imported?.state === 'paused',
                `imported schedule not paused: ${JSON.stringify(imported)}`);
    assertCheck(imported?.nextRun === null || imported?.nextRun === undefined,
                `imported schedule kept next-run: ${JSON.stringify(imported)}`);
    const importedRuns = verifyD.runs.filter((r) => r.scheduleId === 'sched-backup');
    checks.importDefaultPaused = imported?.state === 'paused'
      && (imported?.nextRun === null || imported?.nextRun === undefined);
    checks.importDidNotRun = importedRuns.length === 0;
    assertCheck(checks.importDefaultPaused, 'imported schedule is not paused');
    assertCheck(checks.importDidNotRun,
      `imported schedule produced a run: ${JSON.stringify(importedRuns)}`);

    // Start destination app; UI shows paused schedule, no auto runs.
    console.log('E2E16 checkpoint: before appD start');
    const appD = await startApp({ ...appOptions, root: rootD, port: 0,
                                 keepTemp: true });
    try {
      const ctxD = await browser.newContext({ timezoneId: 'Asia/Shanghai' });
      const pageD = await ctxD.newPage();
      await goto(pageD, appD.url, '/settings');
      await waitForSync(pageD);
      await openScheduleTab(pageD);
      const card = await scheduleCard(pageD, 'E2E16 备份源');
      const text = await cardText(card);
      assertCheck(text.includes('已暂停'), `imported card not paused: ${text}`);
      checks.importedCardNotEnabled = !/已启用/.test(text);
      assertCheck(checks.importedCardNotEnabled,
        `imported card shows an enabled state: ${text}`);
      await shot(pageD, EVIDENCE_DIR, '08-import-paused');
      await ctxD.close().catch(() => {});
    } finally {
      await appD.close().catch(() => {});
    }
  } finally {
    if (appC) await appC.close().catch(() => {});
  }

  evidence.finishedAt = new Date().toISOString();
  const ok = checksOk(checks);
  assertCheck(ok, `E2E16 failed checks: ${JSON.stringify(checks)}`);
  evidence.ok = ok;
  writeEvidence();
  } catch (error) {
    evidence.finishedAt = new Date().toISOString();
    evidence.ok = false;
    evidence.error = String(error?.message || error);
    try {
      writeEvidence();
    } catch {
      // Preserve the original failure; evidence writing is best effort.
    }
    throw error;
  } finally {
    await model.close().catch(() => {});
  }
});
